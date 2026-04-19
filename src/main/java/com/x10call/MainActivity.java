package com.x10call;

import android.app.Activity;
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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.Random;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final String TAG = "X10Call";
    private static final int FRAME_WIDTH  = 320;
    private static final int FRAME_HEIGHT = 240;
    private static final int JPEG_QUALITY = 35;
    private static final int TARGET_FPS   = 12;
    private static final long FRAME_INTERVAL_MS = 1000 / TARGET_FPS;

    private SurfaceView  localPreview;
    private ImageView    remoteView;
    private TextView     statusText, roomCodeText;
    private EditText     roomInput;
    private Button       hostBtn, joinBtn, hangupBtn;
    private Camera        camera;
    private SurfaceHolder surfaceHolder;
    private boolean       cameraRunning = false;
    private byte[]        yuvBuffer;
    private ServerSocket     serverSocket;
    private Socket           connSocket;
    private DataOutputStream outStream;
    private DataInputStream  inStream;
    private boolean          connected = false;
    private int              roomCode  = 0;
    private Handler uiHandler = new Handler();
    private Thread  sendThread, recvThread, serverThread, connectThread;
    private final Object frameLock        = new Object();
    private byte[]       pendingFrameJpeg = null;
    private long         lastSendTime     = 0;
    private PowerManager.WakeLock wakeLock;

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

    private void startHost() {
        roomCode = 100000 + new Random().nextInt(899999);
        setButtonsForCall(false);
        roomCodeText.setText("Room: " + roomCode);
        setStatus("Starting...");
        serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String myIp = getLocalIp();
                    if (myIp == null) { setStatusUI("No network."); return; }
                    int port = 10000 + (roomCode % 50000);
                    serverSocket = new ServerSocket(port);
                    serverSocket.setSoTimeout(180000);
                    boolean posted = postRendezvous(roomCode, myIp + ":" + port);
                    if (!posted) { setStatusUI("Rendezvous failed. Check internet."); return; }
                    setStatusUI("Your code: " + roomCode + "  Waiting...");
                    connSocket = serverSocket.accept();
                    connSocket.setTcpNoDelay(true);
                    outStream = new DataOutputStream(connSocket.getOutputStream());
                    inStream  = new DataInputStream(connSocket.getInputStream());
                    onConnected();
                } catch (Exception e) {
                    setStatusUI("Error: " + e.getMessage());
                    setButtonsReady();
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

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
                    outStream = new DataOutputStream(connSocket.getOutputStream());
                    inStream  = new DataInputStream(connSocket.getInputStream());
                    onConnected();
                } catch (Exception e) {
                    setStatusUI("Failed: " + e.getMessage());
                    setButtonsReady();
                }
            }
        });
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void onConnected() {
        connected = true;
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        uiHandler.post(new Runnable() {
            public void run() { hangupBtn.setEnabled(true); statusText.setVisibility(View.GONE); }
        });
        startSendThread();
        startRecvThread();
    }

    private void startSendThread() {
        sendThread = new Thread(new Runnable() {
            public void run() {
                while (connected) {
                    byte[] frame = null;
                    synchronized (frameLock) {
                        if (pendingFrameJpeg != null) { frame = pendingFrameJpeg; pendingFrameJpeg = null; }
                    }
                    if (frame != null) {
                        try { outStream.writeInt(frame.length); outStream.write(frame); outStream.flush(); }
                        catch (Exception e) { if (connected) hangup(); break; }
                    } else {
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    }
                }
            }
        });
        sendThread.setDaemon(true);
        sendThread.start();
    }

    private void startRecvThread() {
        recvThread = new Thread(new Runnable() {
            public void run() {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                while (connected) {
                    try {
                        int len = inStream.readInt();
                        if (len <= 0 || len > 200000) continue;
                        byte[] buf = new byte[len];
                        inStream.readFully(buf);
                        final Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len, opts);
                        if (bmp != null) {
                            uiHandler.post(new Runnable() { public void run() { remoteView.setImageBitmap(bmp); } });
                        }
                    } catch (Exception e) { if (connected) hangup(); break; }
                }
            }
        });
        recvThread.setDaemon(true);
        recvThread.setPriority(Thread.MAX_PRIORITY - 1);
        recvThread.start();
    }

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

    private boolean postRendezvous(int code, String value) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://ntfy.sh/x10call" + code);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "text/plain");
            byte[] body = value.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            return conn.getResponseCode() >= 200 && conn.getResponseCode() < 300;
        } catch (Exception e) { return false; }
        finally { if (conn != null) conn.disconnect(); }
    }

    private String getRendezvous(int code) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://ntfy.sh/x10call" + code + "/json?poll=1");
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
            int msgIdx = response.lastIndexOf("\"message\":\"");
            if (msgIdx == -1) return null;
            int start = msgIdx + 11;
            int end = response.indexOf("\"", start);
            if (end == -1) return null;
            return response.substring(start, end);
        } catch (Exception e) { return null; }
        finally { if (conn != null) conn.disconnect(); }
    }

    private String getLocalIp() {
        try {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress("8.8.8.8", 80), 3000);
            String ip = s.getLocalAddress().getHostAddress();
            s.close();
            return ip;
        } catch (Exception e) {
            try {
                java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = ifaces.nextElement();
                    java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && !addr.getHostAddress().contains(":"))
                            return addr.getHostAddress();
                    }
                }
            } catch (Exception ex) {}
        }
        return null;
    }

    public void surfaceCreated(SurfaceHolder holder) { openCamera(holder); }
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
    public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }

    private void openCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            p.setPreviewSize(FRAME_WIDTH, FRAME_HEIGHT);
            p.setPreviewFormat(ImageFormat.NV21);
            try { p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF); } catch (Exception ignored) {}
            try { p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED); } catch (Exception ignored) {}
            camera.setParameters(p);
            camera.setPreviewDisplay(holder);
            yuvBuffer = new byte[FRAME_WIDTH * FRAME_HEIGHT * 3 / 2];
            camera.addCallbackBuffer(yuvBuffer);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            cameraRunning = true;
        } catch (Exception e) { setStatus("Camera error: " + e.getMessage()); }
    }

    private void closeCamera() {
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); camera.stopPreview(); camera.release(); } catch (Exception ignored) {}
            camera = null; cameraRunning = false;
        }
    }

    private void hangup() {
        connected = false;
        try { if (connSocket   != null) connSocket.close();   } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        setStatusUI("Call ended");
        setButtonsReady();
        uiHandler.post(new Runnable() {
            public void run() { remoteView.setImageBitmap(null); statusText.setVisibility(View.VISIBLE); roomCodeText.setText(""); }
        });
    }

    private void setStatus(final String msg) { statusText.setText(msg); statusText.setVisibility(View.VISIBLE); }
    private void setStatusUI(final String msg) { uiHandler.post(new Runnable() { public void run() { setStatus(msg); } }); }
    private void setButtonsForCall(boolean enabled) {
        hostBtn.setEnabled(enabled); joinBtn.setEnabled(enabled); roomInput.setEnabled(enabled); hangupBtn.setEnabled(!enabled);
    }
    private void setButtonsReady() {
        uiHandler.post(new Runnable() {
            public void run() { hostBtn.setEnabled(true); joinBtn.setEnabled(true); roomInput.setEnabled(true); hangupBtn.setEnabled(false); }
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); hangup(); closeCamera(); }
    @Override protected void onPause()   { super.onPause(); if (!connected) closeCamera(); }
    @Override protected void onResume()  { super.onResume(); if (!cameraRunning && surfaceHolder != null) openCamera(surfaceHolder); }
}
