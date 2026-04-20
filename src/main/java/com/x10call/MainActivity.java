package com.x10call;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
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
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
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

    private static final int   FRAME_WIDTH        = 320;
    private static final int   FRAME_HEIGHT       = 240;
    private static final int   JPEG_QUALITY       = 35;
    private static final int   TARGET_FPS         = 12;
    private static final long  FRAME_INTERVAL_MS  = 1000 / TARGET_FPS;

    private static final int  AUDIO_SAMPLE_RATE  = 8000;
    private static final int  AUDIO_CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO;
    private static final int  AUDIO_CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO;
    private static final int  AUDIO_FORMAT       = AudioFormat.ENCODING_PCM_16BIT;
    private static final int  AUDIO_BUFFER_MS    = 160;
    private static final int  AUDIO_BUFFER_BYTES = AUDIO_SAMPLE_RATE * 2 * AUDIO_BUFFER_MS / 1000;

    private static final int PKT_VIDEO = 1;
    private static final int PKT_AUDIO = 2;

    // Pre-call
    private View        preCallLayout;
    private View        roomCodeCard;
    private SurfaceView localPreview;
    private TextView    statusText;
    private TextView    roomCodeText;
    private EditText    roomInput;
    private TextView    hostBtn;
    private TextView    joinBtn;

    // In-call
    private View        inCallLayout;
    private ImageView   remoteView;
    private TextView    callStatsText;
    private View        callControls;   // bottom bar, toggled by menu key / tap
    private TextView    muteBtn;
    private TextView    cameraBtn;
    private TextView    rotateBtn;
    private TextView    hangupBtn;
    private boolean     controlsVisible = false;

    // Fonts
    private Typeface robotoRegular;
    private Typeface robotoLight;

    // Camera
    private Camera        camera;
    private SurfaceHolder surfaceHolder;
    private boolean       cameraRunning = false;
    private byte[]        yuvBuffer;

    // Audio
    private AudioRecord  audioRecord;
    private AudioTrack   audioTrack;
    private Thread       audioSendThread;
    private boolean      audioRunning = false;
    private boolean      micMuted     = false;
    private boolean      cameraMuted  = false;

    // Video rotation
    private int remoteRotation = 0;

    // Network
    private ServerSocket     serverSocket;
    private Socket           connSocket;
    private DataOutputStream outStream;
    private DataInputStream  inStream;
    private boolean          connected = false;
    private int              roomCode  = 0;

    // Threading
    private Handler uiHandler = new Handler();
    private Thread  videoSendThread, recvThread, serverThread, connectThread;

    // Frame buffer
    private final Object frameLock        = new Object();
    private byte[]       pendingFrameJpeg = null;
    private long         lastSendTime     = 0;

    // Output lock
    private final Object outLock = new Object();

    // Wake lock
    private PowerManager.WakeLock wakeLock;

    // Stats
    private long     callStartMs   = 0;
    private long     bytesReceived = 0;
    private Runnable statsRunnable;

    // Auto-hide controls after 4 seconds of inactivity
    private Runnable hideControlsRunnable;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);

        // Load Roboto from assets — one allocation, no library needed
        try {
            robotoRegular = Typeface.createFromAsset(getAssets(), "fonts/Roboto-Regular.ttf");
            robotoLight   = Typeface.createFromAsset(getAssets(), "fonts/Roboto-Light.ttf");
        } catch (Exception e) {
            robotoRegular = Typeface.DEFAULT;
            robotoLight   = Typeface.DEFAULT;
        }

        // Wire pre-call views
        preCallLayout = findViewById(R.id.preCallLayout);
        roomCodeCard  = findViewById(R.id.roomCodeCard);
        localPreview  = (SurfaceView) findViewById(R.id.localPreview);
        statusText    = (TextView)    findViewById(R.id.statusText);
        roomCodeText  = (TextView)    findViewById(R.id.roomCodeText);
        roomInput     = (EditText)    findViewById(R.id.roomInput);
        hostBtn       = (TextView)    findViewById(R.id.hostBtn);
        joinBtn       = (TextView)    findViewById(R.id.joinBtn);

        // Wire in-call views
        inCallLayout  = findViewById(R.id.inCallLayout);
        remoteView    = (ImageView)   findViewById(R.id.remoteView);
        callStatsText = (TextView)    findViewById(R.id.callStatsText);
        callControls  = findViewById(R.id.callControls);
        muteBtn       = (TextView)    findViewById(R.id.muteBtn);
        cameraBtn     = (TextView)    findViewById(R.id.cameraBtn);
        rotateBtn     = (TextView)    findViewById(R.id.rotateBtn);
        hangupBtn     = (TextView)    findViewById(R.id.hangupBtn);

        // Apply fonts
        font(statusText,    robotoLight);
        font(roomCodeText,  robotoRegular);
        font(roomInput,     robotoLight);
        font(hostBtn,       robotoRegular);
        font(joinBtn,       robotoRegular);
        font(callStatsText, robotoLight);
        font(muteBtn,       robotoRegular);
        font(cameraBtn,     robotoRegular);
        font(rotateBtn,     robotoRegular);
        font(hangupBtn,     robotoRegular);

        remoteView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        surfaceHolder = localPreview.getHolder();
        surfaceHolder.addCallback(this);
        try { surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); } catch (Exception ignored) {}

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "x10call:wakelock");

        // Pre-call buttons
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

        // Tap remote video to toggle controls
        remoteView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleControls(); }
        });

        // In-call control buttons
        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                micMuted = !micMuted;
                muteBtn.setText(micMuted ? "Unmute" : "Mute");
                scheduleHideControls();
            }
        });
        cameraBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cameraMuted = !cameraMuted;
                cameraBtn.setText(cameraMuted ? "Cam on" : "Cam off");
                scheduleHideControls();
            }
        });
        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                remoteRotation = (remoteRotation + 90) % 360;
                scheduleHideControls();
            }
        });
        hangupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hangup(); }
        });

        // Speaker on from the start
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);
        am.setSpeakerphoneOn(true);

        showPreCall();
    }

    // ── HARDWARE KEY INTERCEPT ─────────────────────────────────────────────────
    // The X10 hardware menu key triggers onKeyDown with KEYCODE_MENU.
    // We use it to toggle the call controls bar when in a call.
    // KEYCODE_BACK dismisses controls if visible, otherwise default back behaviour.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (connected) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                toggleControls();
                return true;  // consumed — don't let system handle it
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (controlsVisible) {
                    hideControls();
                    return true;
                }
                // If controls hidden, back key does nothing (prevents accidental exit)
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── CONTROLS VISIBILITY ───────────────────────────────────────────────────
    private void toggleControls() {
        if (controlsVisible) hideControls();
        else showControls();
    }

    private void showControls() {
        callControls.setVisibility(View.VISIBLE);
        controlsVisible = true;
        scheduleHideControls();
    }

    private void hideControls() {
        callControls.setVisibility(View.GONE);
        controlsVisible = false;
        cancelHideControls();
    }

    private void scheduleHideControls() {
        cancelHideControls();
        hideControlsRunnable = new Runnable() {
            public void run() { if (connected) hideControls(); }
        };
        uiHandler.postDelayed(hideControlsRunnable, 4000);
    }

    private void cancelHideControls() {
        if (hideControlsRunnable != null) {
            uiHandler.removeCallbacks(hideControlsRunnable);
            hideControlsRunnable = null;
        }
    }

    // ── SCREEN SWITCHING ──────────────────────────────────────────────────────
    private void showPreCall() {
        preCallLayout.setVisibility(View.VISIBLE);
        inCallLayout.setVisibility(View.GONE);
        stopStatsUpdater();
        cancelHideControls();
    }

    private void showInCall() {
        preCallLayout.setVisibility(View.GONE);
        inCallLayout.setVisibility(View.VISIBLE);
        callStartMs   = SystemClock.elapsedRealtime();
        bytesReceived = 0;
        startStatsUpdater();
        // Reset button labels
        muteBtn.setText("Mute");
        cameraBtn.setText("Cam off");
    }

    // ── STATS ─────────────────────────────────────────────────────────────────
    private void startStatsUpdater() {
        statsRunnable = new Runnable() {
            public void run() {
                if (!connected) return;
                long s = (SystemClock.elapsedRealtime() - callStartMs) / 1000;
                callStatsText.setText(s / 60 + ":" + String.format("%02d", s % 60)
                    + " · " + fmtBytes(bytesReceived));
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.post(statsRunnable);
    }

    private void stopStatsUpdater() {
        if (statsRunnable != null) { uiHandler.removeCallbacks(statsRunnable); statsRunnable = null; }
    }

    private String fmtBytes(long b) {
        if (b < 1024)    return b + "B";
        if (b < 1048576) return (b / 1024) + "KB";
        return String.format("%.1fMB", b / 1048576f);
    }

    // ── FONT HELPER ───────────────────────────────────────────────────────────
    private void font(TextView tv, Typeface tf) {
        if (tv != null && tf != null) tv.setTypeface(tf);
    }

    // ── HOST ──────────────────────────────────────────────────────────────────
    private void startHost() {
        roomCode = 100000 + new Random().nextInt(899999);
        hostBtn.setEnabled(false);
        joinBtn.setEnabled(false);
        roomInput.setEnabled(false);
        roomCodeCard.setVisibility(View.VISIBLE);
        roomCodeText.setText("" + roomCode);
        setStatus("Waiting for caller...");

        serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String myIp = getLocalIp();
                    if (myIp == null) { setStatusUI("No network."); resetButtons(); return; }
                    int port = 10000 + (roomCode % 50000);
                    serverSocket = new ServerSocket(port);
                    serverSocket.setSoTimeout(180000);
                    if (!postRendezvous(roomCode, myIp + ":" + port)) {
                        setStatusUI("Rendezvous failed."); resetButtons(); return;
                    }
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
                    resetButtons();
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    // ── JOIN ──────────────────────────────────────────────────────────────────
    private void startJoin(final int code) {
        roomCode = code;
        hostBtn.setEnabled(false);
        joinBtn.setEnabled(false);
        roomInput.setEnabled(false);
        setStatus("Looking up room...");

        connectThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String payload = null;
                    for (int i = 0; i < 20; i++) {
                        payload = getRendezvous(code);
                        if (payload != null && payload.contains(":")) break;
                        setStatusUI("Waiting for host... (" + (i + 1) + ")");
                        Thread.sleep(2000);
                    }
                    if (payload == null) { setStatusUI("Room not found."); resetButtons(); return; }
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
                    resetButtons();
                }
            }
        });
        connectThread.setDaemon(true);
        connectThread.start();
    }

    // ── CONNECTED ─────────────────────────────────────────────────────────────
    private void onConnected() {
        connected = true;
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        uiHandler.post(new Runnable() {
            public void run() {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setMode(AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(true);
                showInCall();
            }
        });
        startVideoSendThread();
        startRecvThread();
        startAudio();
    }

    // ── VIDEO SEND ────────────────────────────────────────────────────────────
    private void startVideoSendThread() {
        videoSendThread = new Thread(new Runnable() {
            public void run() {
                while (connected) {
                    byte[] frame = null;
                    synchronized (frameLock) {
                        if (pendingFrameJpeg != null) { frame = pendingFrameJpeg; pendingFrameJpeg = null; }
                    }
                    if (frame != null) {
                        if (cameraMuted) frame = getBlackFrame();
                        try {
                            synchronized (outLock) {
                                outStream.writeByte(PKT_VIDEO);
                                outStream.writeInt(frame.length);
                                outStream.write(frame);
                                outStream.flush();
                            }
                        } catch (Exception e) { if (connected) hangup(); break; }
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

    private byte[] blackFrameCache = null;
    private byte[] getBlackFrame() {
        if (blackFrameCache == null) {
            Bitmap b = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.RGB_565);
            b.eraseColor(0xFF000000);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            b.compress(Bitmap.CompressFormat.JPEG, 20, baos);
            b.recycle();
            blackFrameCache = baos.toByteArray();
        }
        return blackFrameCache;
    }

    // ── RECV ──────────────────────────────────────────────────────────────────
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
                        bytesReceived += len;

                        if (pktType == PKT_VIDEO) {
                            Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len, opts);
                            if (bmp != null) {
                                final Bitmap out = applyRotation(bmp, remoteRotation);
                                uiHandler.post(new Runnable() {
                                    public void run() { remoteView.setImageBitmap(out); }
                                });
                            }
                        } else if (pktType == PKT_AUDIO) {
                            if (audioTrack != null && audioRunning) audioTrack.write(buf, 0, len);
                        }
                    } catch (Exception e) { if (connected) hangup(); break; }
                }
            }
        });
        recvThread.setDaemon(true);
        recvThread.setPriority(Thread.MAX_PRIORITY - 1);
        recvThread.start();
    }

    private Bitmap applyRotation(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
        src.recycle();
        return r;
    }

    // ── AUDIO — STREAM_MUSIC → loudspeaker ───────────────────────────────────
    private void startAudio() {
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT);
        int recBuf = Math.max(minBuf, AUDIO_BUFFER_BYTES);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
        } catch (Exception e) {
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
            } catch (Exception e2) { Log.e(TAG, "AudioRecord unavailable"); return; }
        }

        int playBuf = Math.max(
            AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT),
            AUDIO_BUFFER_BYTES);

        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT,
            playBuf, AudioTrack.MODE_STREAM);

        audioRunning = true;
        audioRecord.startRecording();
        audioTrack.play();

        audioSendThread = new Thread(new Runnable() {
            public void run() {
                byte[] buf    = new byte[AUDIO_BUFFER_BYTES];
                byte[] silence = new byte[AUDIO_BUFFER_BYTES];
                while (connected && audioRunning) {
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) {
                        byte[] send = micMuted ? silence : buf;
                        try {
                            synchronized (outLock) {
                                outStream.writeByte(PKT_AUDIO);
                                outStream.writeInt(read);
                                outStream.write(send, 0, read);
                                outStream.flush();
                            }
                        } catch (Exception e) { if (connected) hangup(); break; }
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

    // ── CAMERA FRAME CALLBACK ─────────────────────────────────────────────────
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

    // ── HANG UP ───────────────────────────────────────────────────────────────
    private void hangup() {
        connected = false;
        stopAudio();
        stopStatsUpdater();
        cancelHideControls();
        try { if (connSocket   != null) connSocket.close();   } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setSpeakerphoneOn(false);
        am.setMode(AudioManager.MODE_NORMAL);
        uiHandler.post(new Runnable() {
            public void run() {
                remoteView.setImageBitmap(null);
                roomCodeCard.setVisibility(View.GONE);
                roomCodeText.setText("");
                hideControls();
                showPreCall();
                resetButtons();
                setStatus("Call ended");
            }
        });
    }

    // ── UI HELPERS ────────────────────────────────────────────────────────────
    private void setStatus(final String msg) { if (statusText != null) statusText.setText(msg); }
    private void setStatusUI(final String msg) {
        uiHandler.post(new Runnable() { public void run() { setStatus(msg); } });
    }
    private void resetButtons() {
        uiHandler.post(new Runnable() {
            public void run() {
                if (hostBtn  != null) hostBtn.setEnabled(true);
                if (joinBtn  != null) joinBtn.setEnabled(true);
                if (roomInput != null) roomInput.setEnabled(true);
            }
        });
    }

    // ── RENDEZVOUS ────────────────────────────────────────────────────────────
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
            return conn.getResponseCode() >= 200 && conn.getResponseCode() < 300;
        } catch (Exception e) { Log.e(TAG, "Post error: " + e.getMessage()); return false; }
        finally { if (conn != null) conn.disconnect(); }
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
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[512]; int n;
            while ((n = conn.getInputStream().read(tmp)) != -1) baos.write(tmp, 0, n);
            String response = baos.toString("UTF-8").trim();
            int idx = response.lastIndexOf("\"message\":\"");
            if (idx == -1) return null;
            int s = idx + 11, e = response.indexOf("\"", s);
            return e == -1 ? null : response.substring(s, e);
        } catch (Exception e) { Log.e(TAG, "Get error: " + e.getMessage()); return null; }
        finally { if (conn != null) conn.disconnect(); }
    }

    // ── IP ────────────────────────────────────────────────────────────────────
    private String getLocalIp() {
        try {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress("8.8.8.8", 80), 3000);
            String ip = s.getLocalAddress().getHostAddress();
            s.close(); return ip;
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
            } catch (Exception ex) { Log.e(TAG, "IP error", ex); }
        }
        return null;
    }

    // ── CAMERA ────────────────────────────────────────────────────────────────
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        uiHandler.postDelayed(new Runnable() { public void run() { openCamera(surfaceHolder); } }, 400);
    }
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (camera != null) {
            try { camera.stopPreview(); camera.setPreviewDisplay(holder); camera.startPreview(); }
            catch (Exception ignored) {}
        }
    }
    public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }

    private void openCamera(SurfaceHolder holder) {
        if (cameraRunning || holder == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 9) {
                int n = Camera.getNumberOfCameras();
                Camera.CameraInfo info = new Camera.CameraInfo();
                int frontIdx = -1, backIdx = -1;
                for (int i = 0; i < n; i++) {
                    Camera.getCameraInfo(i, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && frontIdx == -1) frontIdx = i;
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK  && backIdx  == -1) backIdx  = i;
                }
                int chosen = (frontIdx != -1) ? frontIdx : (backIdx != -1 ? backIdx : 0);
                camera = Camera.open(chosen);
            } else {
                camera = Camera.open();
            }
            if (camera == null) { setStatus("No camera."); return; }

            Camera.Parameters p = camera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = sizes.get(0);
            int bestDiff = Math.abs(best.width - FRAME_WIDTH) + Math.abs(best.height - FRAME_HEIGHT);
            for (Camera.Size sz : sizes) {
                int diff = Math.abs(sz.width - FRAME_WIDTH) + Math.abs(sz.height - FRAME_HEIGHT);
                if (diff < bestDiff) { best = sz; bestDiff = diff; }
            }
            p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            try {
                List<int[]> ranges = p.getSupportedPreviewFpsRange();
                if (ranges != null && !ranges.isEmpty()) {
                    int[] lowest = ranges.get(0);
                    for (int[] r : ranges) { if (r[1] < lowest[1]) lowest = r; }
                    p.setPreviewFpsRange(lowest[0], lowest[1]);
                }
            } catch (Exception ignored) {}
            try { p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);    } catch (Exception ignored) {}
            try { p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);  } catch (Exception ignored) {}
            try { p.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); } catch (Exception ignored) {}
            camera.setParameters(p);
            camera.setPreviewDisplay(holder);
            int bufSize = best.width * best.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            yuvBuffer = new byte[bufSize];
            camera.addCallbackBuffer(yuvBuffer);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            cameraRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "Camera error", e);
            setStatus("Camera error: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); camera.stopPreview(); camera.release(); }
            catch (Exception ignored) {}
            camera = null; cameraRunning = false;
        }
    }

    // ── LIFECYCLE ─────────────────────────────────────────────────────────────
    @Override protected void onDestroy() { super.onDestroy(); hangup(); closeCamera(); }
    @Override protected void onPause()   { super.onPause();   if (!connected) closeCamera(); }
    @Override protected void onResume()  {
        super.onResume();
        if (!cameraRunning && surfaceHolder != null) {
            uiHandler.postDelayed(new Runnable() { public void run() { openCamera(surfaceHolder); } }, 400);
        }
    }
}
