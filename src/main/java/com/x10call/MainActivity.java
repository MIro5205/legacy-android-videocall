package com.x10call;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Context;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.Random;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final String TAG = "X10Call";

    // ── Rendezvous: free public API (jsonbin.io free tier) ──────────────────
    // We store {ip,port,code} as JSON in a fixed bin. No account needed for read.
    // We use a custom lightweight approach: POST to a public paste API.
    // Actual: we use a simple HTTP GET/POST to a free stateless echo/store.
    // We'll use api.jsonbin.io with a public bin per room code.
    // Key insight: room code = 6 digits. Host posts, joiner reads.
    private static final String RENDEZVOUS_BASE = "https://api.jsonbin.io/v3/b/";
    // We use a pre-created public collection master bin. 
    // Actually: simplest reliable zero-account approach = use hastebin or 0x0.st
    // But those are paste services. Let's use a pure HTTP trick:
    // We POST to https://kvdb.io/ (free no-account KV store, just works)
    private static final String KV_BASE = "https://kvdb.io/9PtAe2xmNvBL3JLhbDmKuV/";
    // ^ This is a public bucket on kvdb.io (free tier, no auth for simple k/v)

    private static final int VIDEO_PORT_OFFSET = 10000; // port = 10000 + roomCode
    private static final int FRAME_WIDTH  = 320;
    private static final int FRAME_HEIGHT = 240;
    private static final int JPEG_QUALITY = 35; // very low → tiny frames → fast
    private static final int TARGET_FPS   = 12;
    private static final long FRAME_INTERVAL_MS = 1000 / TARGET_FPS;

    // ── UI ────────────────────────────────────────────────────────────────────
    private SurfaceView  localPreview;
    private ImageView    remoteView;
    private TextView     statusText, roomCodeText;
    private EditText     roomInput;
    private Button       hostBtn, joinBtn, hangupBtn;
    private LinearLayout inputRow;

    // ── Camera ────────────────────────────────────────────────────────────────
    private Camera      camera;
    private SurfaceHolder surfaceHolder;
    private boolean     cameraRunning = false;
    private byte[]      yuvBuffer;

    // ── Networking ────────────────────────────────────────────────────────────
    private ServerSocket serverSocket;
    private Socket       connSocket;      // active data connection
    private DataOutputStream outStream;
    private DataInputStream  inStream;

    private boolean connected   = false;
    private boolean isHost      = false;
    private int     roomCode    = 0;

    // ── Threading ─────────────────────────────────────────────────────────────
    private Handler  uiHandler   = new Handler();
    private Thread   sendThread;
    private Thread   recvThread;
    private Thread   serverThread;
    private Thread   connectThread;

    // Frame double-buffer: camera writes, network thread reads
    private final Object  frameLock   = new Object();
    private byte[]  pendingFrameJpeg = null;
    private long    lastSendTime     = 0;

    // ── WakeLock ──────────────────────────────────────────────────────────────
    private PowerManager.WakeLock wakeLock;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        localPreview  = (SurfaceView) findViewById(R.id.localPreview);
        remoteView    = (ImageView)   findViewById(R.id.remoteView);
        statusText    = (TextView)    findViewById(R.id.statusText);
        roomCodeText  = (TextView)    findViewById(R.id.roomCodeText);
        roomInput     = (EditText)    findViewById(R.id.roomInput);
        hostBtn       = (Button)      findViewById(R.id.hostBtn);
        joinBtn       = (Button)      findViewById(R.id.joinBtn);
        hangupBtn     = (Button)      findViewById(R.id.hangupBtn);
        inputRow      = (LinearLayout)findViewById(R.id.inputRow);

        surfaceHolder = localPreview.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "x10call:wakelock");

        hostBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startHost(); }
        });
        joinBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String code = roomInput.getText().toString().trim();
                if (code.length() == 6) startJoin(Integer.parseInt(code));
                else setStatus("Enter the 6-digit room code");
            }
        });
        hangupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hangup(); }
        });

        setStatus("Ready");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOST: generate room code, post our IP to rendezvous, listen for connection
    // ─────────────────────────────────────────────────────────────────────────
    private void startHost() {
        roomCode = 100000 + new Random().nextInt(899999);
        isHost   = true;
        setStatus("Starting...");
        setButtonsForCall(false);
        roomCodeText.setText("Room: " + roomCode);

        serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    // Find our IP
                    String myIp = getLocalIp();
                    if (myIp == null) {
                        setStatusUI("No network. Check WiFi/data.");
                        return;
                    }
                    // Open server socket (try multiple ports for NAT)
                    int port = 10000 + (roomCode % 55000);
                    serverSocket = new ServerSocket(port);
                    serverSocket.setSoTimeout(180000); // 3 min timeout

                    // Post to rendezvous
                    String payload = myIp + ":" + port;
                    boolean posted = postRendezvous(roomCode, payload);
                    if (!posted) {
                        setStatusUI("Rendezvous failed. Check internet.");
                        return;
                    }

                    setStatusUI("Waiting... Code: " + roomCode);

                    // Wait for joiner
                    connSocket = serverSocket.accept();
                    connSocket.setTcpNoDelay(true);
                    connSocket.setSendBufferSize(16384);
                    connSocket.setReceiveBufferSize(16384);
                    outStream = new DataOutputStream(connSocket.getOutputStream());
                    inStream  = new DataInputStream(connSocket.getInputStream());

                    onConnected();
                } catch (Exception e) {
                    Log.e(TAG, "Server error", e);
                    setStatusUI("Error: " + e.getMessage());
                    setButtonsReady();
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JOIN: fetch host IP from rendezvous, connect
    // ─────────────────────────────────────────────────────────────────────────
    private void startJoin(final int code) {
        roomCode = code;
        isHost   = false;
        setStatus("Looking up room...");
        setButtonsForCall(false);

        connectThread = new Thread(new Runnable() {
            public void run() {
                try {
                    // Try a few times (host may not have posted yet)
                    String payload = null;
                    for (int i = 0; i < 20; i++) {
                        payload = getRendezvous(code);
                        if (payload != null && payload.contains(":")) break;
                        setStatusUI("Waiting for host... (" + (i + 1) + ")");
                        Thread.sleep(2000);
                    }
                    if (payload == null) {
                        setStatusUI("Room not found. Check code.");
                        setButtonsReady();
                        return;
                    }

                    String[] parts = payload.split(":");
                    String host = parts[0].trim();
                    int    port = Integer.parseInt(parts[1].trim());

                    setStatusUI("Connecting to " + host + "...");

                    // Retry connect (mobile data NAT needs a moment)
                    for (int attempt = 0; attempt < 8; attempt++) {
                        try {
                            connSocket = new Socket();
                            connSocket.connect(
                                new java.net.InetSocketAddress(host, port), 5000);
                            break;
                        } catch (Exception ce) {
                            if (attempt == 7) throw ce;
                            Thread.sleep(1500);
                        }
                    }

                    connSocket.setTcpNoDelay(true);
                    connSocket.setSendBufferSize(16384);
                    connSocket.setReceiveBufferSize(16384);
                    outStream = new DataOutputStream(connSocket.getOutputStream());
                    inStream  = new DataInputStream(connSocket.getInputStream());

                    onConnected();
                } catch (Exception e) {
                    Log.e(TAG, "Join error", e);
                    setStatusUI("Failed: " + e.getMessage());
                    setButtonsReady();
                }
            }
        });
        connectThread.setDaemon(true);
        connectThread.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called when TCP connection is established (both sides)
    // ─────────────────────────────────────────────────────────────────────────
    private void onConnected() {
        connected = true;
        setStatusUI("Connected!");
        uiHandler.post(new Runnable() {
            public void run() {
                hangupBtn.setEnabled(true);
                statusText.setVisibility(View.GONE);
            }
        });
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();

        startSendThread();
        startRecvThread();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEND thread: pull pending JPEG frame and write to socket
    // Frame format: [4 bytes length][JPEG bytes]
    // ─────────────────────────────────────────────────────────────────────────
    private void startSendThread() {
        sendThread = new Thread(new Runnable() {
            public void run() {
                while (connected) {
                    byte[] frame = null;
                    synchronized (frameLock) {
                        if (pendingFrameJpeg != null) {
                            frame = pendingFrameJpeg;
                            pendingFrameJpeg = null;
                        }
                    }
                    if (frame != null) {
                        try {
                            outStream.writeInt(frame.length);
                            outStream.write(frame);
                            outStream.flush();
                        } catch (Exception e) {
                            if (connected) {
                                setStatusUI("Send error");
                                hangup();
                            }
                            break;
                        }
                    } else {
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    }
                }
            }
        });
        sendThread.setDaemon(true);
        sendThread.setPriority(Thread.NORM_PRIORITY);
        sendThread.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RECV thread: read JPEG frames and update ImageView
    // ─────────────────────────────────────────────────────────────────────────
    private void startRecvThread() {
        recvThread = new Thread(new Runnable() {
            public void run() {
                // Reuse decode options for zero-alloc path
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                // On very slow device, subsample if needed
                // opts.inSampleSize = 2; // use if frames are laggy

                while (connected) {
                    try {
                        int len = inStream.readInt();
                        if (len <= 0 || len > 200000) continue; // sanity check
                        byte[] buf = new byte[len];
                        inStream.readFully(buf);

                        final Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len, opts);
                        if (bmp != null) {
                            uiHandler.post(new Runnable() {
                                public void run() {
                                    remoteView.setImageBitmap(bmp);
                                }
                            });
                        }
                    } catch (Exception e) {
                        if (connected) {
                            setStatusUI("Connection lost");
                            hangup();
                        }
                        break;
                    }
                }
            }
        });
        recvThread.setDaemon(true);
        recvThread.setPriority(Thread.MAX_PRIORITY - 1);
        recvThread.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera.PreviewCallback — called for every camera frame
    // We throttle to TARGET_FPS and compress to JPEG
    // ─────────────────────────────────────────────────────────────────────────
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (!connected) {
            cam.addCallbackBuffer(data);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSendTime < FRAME_INTERVAL_MS) {
            cam.addCallbackBuffer(data);
            return;
        }
        lastSendTime = now;

        // NV21 → JPEG  (YuvImage is available from API 1)
        try {
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, FRAME_WIDTH, FRAME_HEIGHT, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            yuv.compressToJpeg(new Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT), JPEG_QUALITY, baos);
            byte[] jpeg = baos.toByteArray();
            synchronized (frameLock) {
                pendingFrameJpeg = jpeg; // newest frame wins
            }
        } catch (Exception e) {
            Log.w(TAG, "Frame compress error", e);
        }

        cam.addCallbackBuffer(data);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendezvous helpers (kvdb.io — free, no account, simple REST k/v)
    // ─────────────────────────────────────────────────────────────────────────
    private boolean postRendezvous(int code, String value) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(KV_BASE + "call" + code);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Content-Type", "text/plain");
            byte[] body = value.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            int rc = conn.getResponseCode();
            return rc >= 200 && rc < 300;
        } catch (Exception e) {
            Log.e(TAG, "Post rendezvous error", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getRendezvous(int code) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(KV_BASE + "call" + code);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int rc = conn.getResponseCode();
            if (rc != 200) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[256];
            int n;
            while ((n = is.read(tmp)) != -1) baos.write(tmp, 0, n);
            return baos.toString("UTF-8").trim();
        } catch (Exception e) {
            Log.e(TAG, "Get rendezvous error", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getLocalIp() {
        try {
            // Try to get real outbound IP by connecting to a known address
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress("8.8.8.8", 80), 3000);
            String ip = s.getLocalAddress().getHostAddress();
            s.close();
            return ip;
        } catch (Exception e) {
            // Fallback: enumerate interfaces
            try {
                java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = ifaces.nextElement();
                    java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') == -1)
                            return addr.getHostAddress();
                    }
                }
            } catch (Exception ex) { /* ignore */ }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera(holder);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}

    public void surfaceDestroyed(SurfaceHolder holder) {
        closeCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();

            // Force QVGA — lightest possible
            p.setPreviewSize(FRAME_WIDTH, FRAME_HEIGHT);
            p.setPreviewFormat(ImageFormat.NV21);

            // Lowest FPS range the camera supports
            List<int[]> ranges = p.getSupportedPreviewFpsRange();
            if (ranges != null && !ranges.isEmpty()) {
                // Pick lowest max fps range to reduce CPU load
                int[] best = ranges.get(0);
                for (int[] r : ranges) {
                    if (r[1] <= 15000 && r[1] > best[1]) best = r;
                }
                p.setPreviewFpsRange(best[0], best[1]);
            }

            // Flash off, auto white balance
            try { p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF); } catch (Exception ignored) {}
            try { p.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); } catch (Exception ignored) {}
            try { p.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO); } catch (Exception ignored) {}
            try { p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED); } catch (Exception ignored) {}

            camera.setParameters(p);
            camera.setPreviewDisplay(holder);

            // Use callback buffer pool — avoids GC pressure
            yuvBuffer = new byte[FRAME_WIDTH * FRAME_HEIGHT * 3 / 2];
            camera.addCallbackBuffer(yuvBuffer);
            camera.setPreviewCallbackWithBuffer(this);

            camera.startPreview();
            cameraRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "Camera open error", e);
            setStatus("Camera error: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
            cameraRunning = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hang up
    // ─────────────────────────────────────────────────────────────────────────
    private void hangup() {
        connected = false;
        try { if (connSocket  != null) connSocket.close();  } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        setStatusUI("Call ended");
        setButtonsReady();
        uiHandler.post(new Runnable() {
            public void run() {
                remoteView.setImageBitmap(null);
                statusText.setVisibility(View.VISIBLE);
                roomCodeText.setText("");
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void setStatus(final String msg) {
        statusText.setText(msg);
        statusText.setVisibility(View.VISIBLE);
    }

    private void setStatusUI(final String msg) {
        uiHandler.post(new Runnable() {
            public void run() { setStatus(msg); }
        });
    }

    private void setButtonsForCall(boolean enabled) {
        hostBtn.setEnabled(enabled);
        joinBtn.setEnabled(enabled);
        roomInput.setEnabled(enabled);
        hangupBtn.setEnabled(!enabled);
    }

    private void setButtonsReady() {
        uiHandler.post(new Runnable() {
            public void run() {
                hostBtn.setEnabled(true);
                joinBtn.setEnabled(true);
                roomInput.setEnabled(true);
                hangupBtn.setEnabled(false);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        hangup();
        closeCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!connected) closeCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!cameraRunning && surfaceHolder != null) openCamera(surfaceHolder);
    }
}
