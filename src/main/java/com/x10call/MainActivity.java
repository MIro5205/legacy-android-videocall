package com.x10call;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final String TAG = "X10Call";

    // Video settings — tuned for X10 Mini Pro
    private static final int FRAME_WIDTH    = 320;
    private static final int FRAME_HEIGHT   = 240;
    private static final int JPEG_QUALITY   = 35;
    private static final int TARGET_FPS     = 12;
    private static final long FRAME_INTERVAL_MS = 1000 / TARGET_FPS;

    // Audio settings — narrow band phone quality, minimum CPU
    private static final int AUDIO_SAMPLE_RATE = 8000;   // 8kHz — voice only, lightest possible
    private static final int AUDIO_CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT       = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_BUFFER_MS    = 160;   // 160ms chunks — balance latency vs stability
    private static final int AUDIO_BUFFER_BYTES = AUDIO_SAMPLE_RATE * 2 * AUDIO_BUFFER_MS / 1000; // 2560

    // Packet type tags written before each packet so recv knows what it got
    private static final int PKT_VIDEO = 1;
    private static final int PKT_AUDIO = 2;

    // UI
    private SurfaceView  localPreview;
    private ImageView    remoteView;
    private TextView     statusText, roomCodeText;
    private EditText     roomInput;
    private Button       hostBtn, joinBtn, hangupBtn;

    // Camera
    private Camera        camera;
    private SurfaceHolder surfaceHolder;
    private boolean       cameraRunning = false;
    private byte[]        yuvBuffer;

    // Audio
    private AudioRecord   audioRecord;
    private AudioTrack    audioTrack;
    private Thread        audioSendThread;
    private Thread        audioRecvThread;
    private boolean       audioRunning = false;

    // Network — single TCP connection, multiplexed video+audio with packet type header
    private ServerSocket     serverSocket;
    private Socket           connSocket;
    private DataOutputStream outStream;
    private DataInputStream  inStream;
    private boolean          connected = false;
    private int              roomCode  = 0;

    // Threading
    private Handler uiHandler    = new Handler();
    private Thread  videoSendThread, recvThread, serverThread, connectThread;

    // Frame buffer — newest frame wins, old frames dropped
    private final Object frameLock        = new Object();
    private byte[]       pendingFrameJpeg = null;
    private long         lastSendTime     = 0;

    // Output lock — audio and video both write to outStream, must be synchronized
    private final Object outLock = new Object();

    // WakeLock
    private PowerManager.WakeLock wakeLock;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on during call
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);

        localPreview  = (SurfaceView) findViewById(R.id.localPreview);
        remoteView    = (ImageView)   findViewById(R.id.remoteView);
        statusText    = (TextView)    findViewById(R.id.statusText);
        roomCodeText  = (TextView)    findViewById(R.id.roomCodeText);
        roomInput     = (EditText)    findViewById(R.id.roomInput);
        hostBtn       = (Button)      findViewById(R.id.hostBtn);
        joinBtn       = (Button)      findViewById(R.id.joinBtn);
        hangupBtn     = (Button)      findViewById(R.id.hangupBtn);

        surfaceHolder = localPreview.getHolder();
        surfaceHolder.addCallback(this);
        // SURFACE_TYPE_PUSH_BUFFERS needed for API < 11
        try { surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); } catch (Exception ignored) {}

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

        // Route audio to earpiece by default (like a phone call)
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(false);

        setStatus("Ready");
    }

    // ── HOST ─────────────────────────────────────────────────────────────────
    private void startHost() {
        roomCode = 100000 + new Random().nextInt(899999);
        setButtonsForCall(false);
        roomCodeText.setText("Room: " + roomCode);
        setStatus("Starting...");

        serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String myIp = getLocalIp();
                    if (myIp == null) { setStatusUI("No network."); setButtonsReady(); return; }
                    int port = 10000 + (roomCode % 50000);
                    serverSocket = new ServerSocket(port);
                    serverSocket.setSoTimeout(180000);
                    boolean posted = postRendezvous(roomCode, myIp + ":" + port);
                    if (!posted) { setStatusUI("Rendezvous failed. Check internet."); setButtonsReady(); return; }
                    setStatusUI("Your code: " + roomCode + "\nWaiting for caller...");
                    connSocket = serverSocket.accept();
                    connSocket.setTcpNoDelay(true);
                    connSocket.setSendBufferSize(32768);
                    connSocket.setReceiveBufferSize(32768);
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

    // ── JOIN ─────────────────────────────────────────────────────────────────
    private void startJoin(final int code) {
        roomCode = code;
        setButtonsForCall(false);
        setStatus("Looking up room...");

        connectThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String payload = null;
                    for (int i = 0; i < 20; i++) {
                        payload = getRendezvous(code);
                        if (payload != null && payload.contains(":")) break;
                        setStatusUI("Waiting for host... (" + (i+1) + ")");
                        Thread.sleep(2000);
                    }
                    if (payload == null) { setStatusUI("Room not found."); setButtonsReady(); return; }
                    String[] parts = payload.split(":");
                    String host = parts[0].trim();
                    int    port = Integer.parseInt(parts[1].trim());
                    setStatusUI("Connecting...");
                    for (int attempt = 0; attempt < 8; attempt++) {
                        try {
                            connSocket = new Socket();
                            connSocket.connect(new java.net.InetSocketAddress(host, port), 5000);
                            break;
                        } catch (Exception ce) {
                            if (attempt == 7) throw ce;
                            Thread.sleep(1500);
                        }
                    }
                    connSocket.setTcpNoDelay(true);
                    connSocket.setSendBufferSize(32768);
                    connSocket.setReceiveBufferSize(32768);
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

    // ── CONNECTED ────────────────────────────────────────────────────────────
    private void onConnected() {
        connected = true;
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        uiHandler.post(new Runnable() {
            public void run() {
                hangupBtn.setEnabled(true);
                statusText.setVisibility(View.GONE);
            }
        });
        startVideoSendThread();
        startRecvThread();
        startAudio();
    }

    // ── VIDEO SEND ───────────────────────────────────────────────────────────
    // Packet format: [1 byte type=PKT_VIDEO][4 bytes length][JPEG bytes]
    private void startVideoSendThread() {
        videoSendThread = new Thread(new Runnable() {
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
                            synchronized (outLock) {
                                outStream.writeByte(PKT_VIDEO);
                                outStream.writeInt(frame.length);
                                outStream.write(frame);
                                outStream.flush();
                            }
                        } catch (Exception e) {
                            if (connected) hangup();
                            break;
                        }
                    } else {
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    }
                }
            }
        });
        videoSendThread.setDaemon(true);
        videoSendThread.setPriority(Thread.NORM_PRIORITY);
        videoSendThread.start();
    }

    // ── UNIFIED RECV ─────────────────────────────────────────────────────────
    // Reads packet type byte, then dispatches to video or audio handler
    private void startRecvThread() {
        recvThread = new Thread(new Runnable() {
            public void run() {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                while (connected) {
                    try {
                        int pktType = inStream.readByte() & 0xFF;
                        int len = inStream.readInt();
                        if (len <= 0 || len > 500000) continue;
                        byte[] buf = new byte[len];
                        inStream.readFully(buf);

                        if (pktType == PKT_VIDEO) {
                            final Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len, opts);
                            if (bmp != null) {
                                uiHandler.post(new Runnable() {
                                    public void run() { remoteView.setImageBitmap(bmp); }
                                });
                            }
                        } else if (pktType == PKT_AUDIO) {
                            if (audioTrack != null && audioRunning) {
                                audioTrack.write(buf, 0, len);
                            }
                        }
                    } catch (Exception e) {
                        if (connected) hangup();
                        break;
                    }
                }
            }
        });
        recvThread.setDaemon(true);
        recvThread.setPriority(Thread.MAX_PRIORITY - 1);
        recvThread.start();
    }

    // ── AUDIO ─────────────────────────────────────────────────────────────────
    // Packet format: [1 byte type=PKT_AUDIO][4 bytes length][PCM bytes]
    private void startAudio() {
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT);
        int recBuf = Math.max(minBuf, AUDIO_BUFFER_BYTES);

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
        } catch (Exception e) {
            Log.w(TAG, "AudioRecord failed, trying MIC source");
            try {
                audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
            } catch (Exception e2) {
                Log.e(TAG, "AudioRecord unavailable: " + e2.getMessage());
                return;
            }
        }

        int playBuf = Math.max(
            AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT),
            AUDIO_BUFFER_BYTES);

        audioTrack = new AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT,
            playBuf, AudioTrack.MODE_STREAM);

        audioRunning = true;
        audioRecord.startRecording();
        audioTrack.play();

        // Send thread: mic → network
        audioSendThread = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[AUDIO_BUFFER_BYTES];
                while (connected && audioRunning) {
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) {
                        try {
                            synchronized (outLock) {
                                outStream.writeByte(PKT_AUDIO);
                                outStream.writeInt(read);
                                outStream.write(buf, 0, read);
                                outStream.flush();
                            }
                        } catch (Exception e) {
                            if (connected) hangup();
                            break;
                        }
                    }
                }
            }
        });
        audioSendThread.setDaemon(true);
        audioSendThread.setPriority(Thread.MAX_PRIORITY - 2);
        audioSendThread.start();
    }

    private void stopAudio() {
        audioRunning = false;
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception ignored) {}
        try { if (audioTrack  != null) { audioTrack.stop();  audioTrack.release();  audioTrack  = null; } } catch (Exception ignored) {}
    }

    // ── CAMERA FRAME CALLBACK ────────────────────────────────────────────────
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (!connected) { cam.addCallbackBuffer(data); return; }
        long now = System.currentTimeMillis();
        if (now - lastSendTime < FRAME_INTERVAL_MS) { cam.addCallbackBuffer(data); return; }
        lastSendTime = now;
        try {
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, FRAME_WIDTH, FRAME_HEIGHT, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            yuv.compressToJpeg(new Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT), JPEG_QUALITY, baos);
            synchronized (frameLock) { pendingFrameJpeg = baos.toByteArray(); }
        } catch (Exception e) { Log.w(TAG, "Frame error", e); }
        cam.addCallbackBuffer(data);
    }

    // ── RENDEZVOUS (ntfy.sh plain HTTP — works on Android 2.1+) ─────────────
    private boolean postRendezvous(int code, String value) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://ntfy.sh/x10call" + code);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "text/plain");
            byte[] body = value.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            int rc = conn.getResponseCode();
            return rc >= 200 && rc < 300;
        } catch (Exception e) {
            Log.e(TAG, "Post error: " + e.getMessage());
            return false;
        } finally { if (conn != null) conn.disconnect(); }
    }

    private String getRendezvous(int code) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://ntfy.sh/x10call" + code + "/json?poll=1");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[512];
            int n;
            while ((n = is.read(tmp)) != -1) baos.write(tmp, 0, n);
            String response = baos.toString("UTF-8").trim();
            // ntfy returns newline-delimited JSON — grab last message field
            int msgIdx = response.lastIndexOf("\"message\":\"");
            if (msgIdx == -1) return null;
            int start = msgIdx + 11;
            int end = response.indexOf("\"", start);
            if (end == -1) return null;
            return response.substring(start, end);
        } catch (Exception e) {
            Log.e(TAG, "Get error: " + e.getMessage());
            return null;
        } finally { if (conn != null) conn.disconnect(); }
    }

    // ── IP DETECTION ─────────────────────────────────────────────────────────
    private String getLocalIp() {
        try {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress("8.8.8.8", 80), 3000);
            String ip = s.getLocalAddress().getHostAddress();
            s.close();
            return ip;
        } catch (Exception e) {
            try {
                java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = ifaces.nextElement();
                    java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && !addr.getHostAddress().contains(":"))
                            return addr.getHostAddress();
                    }
                }
            } catch (Exception ex) { Log.e(TAG, "IP enum error", ex); }
        }
        return null;
    }

    // ── CAMERA LIFECYCLE ─────────────────────────────────────────────────────
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        // Delay on slow hardware to let surface fully initialise
        uiHandler.postDelayed(new Runnable() {
            public void run() { openCamera(surfaceHolder); }
        }, 400);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception ignored) {}
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }

    private void openCamera(SurfaceHolder holder) {
        if (cameraRunning || holder == null) return;
        try {
            // API 9+ supports Camera.open(int) for front/back selection
            // API 7-8 only has Camera.open() which opens rear camera
            if (Build.VERSION.SDK_INT >= 9) {
                int numCams = Camera.getNumberOfCameras();
                Camera.CameraInfo info = new Camera.CameraInfo();
                for (int i = 0; i < numCams; i++) {
                    Camera.getCameraInfo(i, info);
                    // Prefer back camera
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        camera = Camera.open(i);
                        break;
                    }
                }
                if (camera == null && numCams > 0) camera = Camera.open(0);
            } else {
                camera = Camera.open();
            }

            if (camera == null) { setStatus("No camera found."); return; }

            Camera.Parameters p = camera.getParameters();

            // Find the closest supported preview size to our target
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = sizes.get(0);
            int bestDiff = Math.abs(best.width - FRAME_WIDTH) + Math.abs(best.height - FRAME_HEIGHT);
            for (Camera.Size s : sizes) {
                int diff = Math.abs(s.width - FRAME_WIDTH) + Math.abs(s.height - FRAME_HEIGHT);
                if (diff < bestDiff) { best = s; bestDiff = diff; }
            }
            p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);

            // Lowest FPS range for minimum CPU heat
            try {
                List<int[]> fpsRanges = p.getSupportedPreviewFpsRange();
                if (fpsRanges != null && !fpsRanges.isEmpty()) {
                    int[] lowest = fpsRanges.get(0);
                    for (int[] r : fpsRanges) { if (r[1] < lowest[1]) lowest = r; }
                    p.setPreviewFpsRange(lowest[0], lowest[1]);
                }
            } catch (Exception ignored) {}

            try { p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF); }  catch (Exception ignored) {}
            try { p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED); } catch (Exception ignored) {}
            try { p.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); } catch (Exception ignored) {}

            camera.setParameters(p);
            camera.setPreviewDisplay(holder);

            // Use buffer pool to avoid GC pressure on tight loop
            // Buffer size uses actual chosen dimensions, not our constants
            int bufSize = best.width * best.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            yuvBuffer = new byte[bufSize];
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

    // ── HANG UP ──────────────────────────────────────────────────────────────
    private void hangup() {
        connected = false;
        stopAudio();
        try { if (connSocket   != null) connSocket.close();   } catch (Exception ignored) {}
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

    // ── UI HELPERS ───────────────────────────────────────────────────────────
    private void setStatus(final String msg) {
        statusText.setText(msg);
        statusText.setVisibility(View.VISIBLE);
    }
    private void setStatusUI(final String msg) {
        uiHandler.post(new Runnable() { public void run() { setStatus(msg); } });
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

    // ── LIFECYCLE ─────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() { super.onDestroy(); hangup(); closeCamera(); }

    @Override
    protected void onPause() { super.onPause(); if (!connected) closeCamera(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (!cameraRunning && surfaceHolder != null) {
            uiHandler.postDelayed(new Runnable() {
                public void run() { openCamera(surfaceHolder); }
            }, 400);
        }
    }
}
