package com.x10call;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Context;

import java.io.BufferedOutputStream;
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

    // ── Video — tuned for 200MHz X10 Mini Pro ────────────────────────────────
    private static final int   FRAME_WIDTH       = 176;   // QCIF — lightest encode on X10
    private static final int   FRAME_HEIGHT      = 144;
    private static final int   JPEG_QUALITY      = 30;
    private static final long  FRAME_INTERVAL_MS = 150;   // ~6.6 fps

    // ── Audio — 8kHz mono PCM ─────────────────────────────────────────────────
    private static final int AUDIO_SAMPLE_RATE  = 8000;
    private static final int AUDIO_CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT       = AudioFormat.ENCODING_PCM_16BIT;
    // 200ms chunks: bigger = fewer flushes = less CPU overhead
    private static final int AUDIO_BUFFER_BYTES = AUDIO_SAMPLE_RATE * 2 * 200 / 1000; // 3200

    // ── Packet tags ───────────────────────────────────────────────────────────
    private static final int PKT_VIDEO = 1;
    private static final int PKT_AUDIO = 2;

    // ── Material You dark colour palette ─────────────────────────────────────
    private static final int COL_BG      = 0xFF1C1B1F;
    private static final int COL_SURFACE = 0xFF2B2930;
    private static final int COL_PRIMARY = 0xFFD0BCFF;
    private static final int COL_ON_SURF = 0xFFE6E1E5;
    private static final int COL_MUTED   = 0xFF938F99;
    private static final int COL_DANGER  = 0xFFFFB4AB;

    // ── UI ────────────────────────────────────────────────────────────────────
    private SurfaceView  localPreview;
    private ImageView    remoteView;
    private TextView     statusText, roomCodeText, quotaText, timerText;
    private EditText     roomInput;
    private Button       hostBtn, joinBtn, hangupBtn, muteBtn, camToggleBtn;

    // ── Camera ────────────────────────────────────────────────────────────────
    private Camera        camera;
    private SurfaceHolder surfaceHolder;
    private boolean       cameraRunning = false;
    private byte[]        yuvBuffer;
    // Reused per session — avoids per-frame ByteArrayOutputStream allocation
    private final ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(8192);

    // ── Audio ─────────────────────────────────────────────────────────────────
    private AudioRecord   audioRecord;
    private AudioTrack    audioTrack;
    private Thread        audioSendThread;
    private boolean       audioRunning = false;

    // ── Network ───────────────────────────────────────────────────────────────
    private ServerSocket     serverSocket;
    private Socket           connSocket;
    private DataOutputStream outStream;
    private DataInputStream  inStream;
    private volatile boolean connected  = false;
    private int              roomCode   = 0;

    // ── Threading ─────────────────────────────────────────────────────────────
    private final Handler uiHandler = new Handler();
    private Thread videoSendThread, recvThread, serverThread, connectThread;

    // ── Frame buffer — newest frame wins ──────────────────────────────────────
    private final Object frameLock        = new Object();
    private byte[]       pendingFrameJpeg = null;
    private long         lastFrameTime    = 0;

    // ── Output lock ───────────────────────────────────────────────────────────
    private final Object outLock = new Object();

    // ── Audio flush batching ──────────────────────────────────────────────────
    private int audioFlushCounter        = 0;
    private static final int FLUSH_EVERY = 4;

    // ── WakeLock ──────────────────────────────────────────────────────────────
    private PowerManager.WakeLock wakeLock;

    // ── Quota & timer ─────────────────────────────────────────────────────────
    private volatile long quotaBytes    = 0;
    private long          callStartTime = 0;
    private Runnable      timerRunnable;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile boolean micMuted  = false;
    private volatile boolean cameraOff = false;
    private volatile boolean hangingUp = false;

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics()));
    }

    private TextView makeLabel(String text, int sizeSp, int color, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setGravity(gravity);
        return tv;
    }

    private Button makeBtn(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(bgColor);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, dp(8), 0, dp(8));
        v.setLayoutParams(lp);
        v.setBackgroundColor(COL_SURFACE);
        return v;
    }

    // ── BUILD UI ──────────────────────────────────────────────────────────────
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        // Video area
        FrameLayout videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        root.addView(videoFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        remoteView = new ImageView(this);
        remoteView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        remoteView.setBackgroundColor(Color.BLACK);
        videoFrame.addView(remoteView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        localPreview = new SurfaceView(this);
        localPreview.setBackgroundColor(0xFF111111);
        FrameLayout.LayoutParams pipLp = new FrameLayout.LayoutParams(dp(80), dp(60));
        pipLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        pipLp.setMargins(0, 0, dp(6), dp(6));
        videoFrame.addView(localPreview, pipLp);

        // Timer + quota row
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irLp.setMargins(0, dp(6), 0, dp(2));
        infoRow.setLayoutParams(irLp);

        timerText = makeLabel("", 13, COL_MUTED, Gravity.LEFT);
        infoRow.addView(timerText, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        quotaText = makeLabel("", 13, COL_MUTED, Gravity.RIGHT);
        infoRow.addView(quotaText);
        root.addView(infoRow);

        // Status
        statusText = makeLabel("Ready", 14, COL_ON_SURF, Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(0, dp(4), 0, dp(2));
        statusText.setLayoutParams(stLp);
        root.addView(statusText);

        // Room code (large, shown while hosting)
        roomCodeText = makeLabel("", 24, COL_PRIMARY, Gravity.CENTER);
        roomCodeText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(roomCodeText);

        root.addView(divider());

        // Join row
        LinearLayout joinRow = new LinearLayout(this);
        joinRow.setOrientation(LinearLayout.HORIZONTAL);
        joinRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams jrLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jrLp.setMargins(0, dp(4), 0, dp(4));
        joinRow.setLayoutParams(jrLp);

        roomInput = new EditText(this);
        roomInput.setHint("6-digit room code");
        roomInput.setHintTextColor(COL_MUTED);
        roomInput.setTextColor(COL_ON_SURF);
        roomInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        roomInput.setBackgroundColor(COL_SURFACE);
        roomInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        roomInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        roomInput.setSingleLine(true);
        joinRow.addView(roomInput, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        joinBtn = makeBtn("Join", COL_SURFACE, COL_PRIMARY);
        LinearLayout.LayoutParams jbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jbLp.setMargins(dp(6), 0, 0, 0);
        joinBtn.setLayoutParams(jbLp);
        joinRow.addView(joinBtn);
        root.addView(joinRow);

        // Host button
        hostBtn = makeBtn("Host new call", COL_PRIMARY, COL_BG);
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbLp.setMargins(0, dp(4), 0, dp(4));
        hostBtn.setLayoutParams(hbLp);
        root.addView(hostBtn);

        root.addView(divider());

        // In-call controls
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.setMargins(0, dp(4), 0, dp(4));
        ctrlRow.setLayoutParams(crLp);

        muteBtn = makeBtn("Mute", COL_SURFACE, COL_ON_SURF);
        muteBtn.setEnabled(false);
        LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mbLp.setMargins(0, 0, dp(4), 0);
        muteBtn.setLayoutParams(mbLp);
        ctrlRow.addView(muteBtn);

        camToggleBtn = makeBtn("Cam off", COL_SURFACE, COL_ON_SURF);
        camToggleBtn.setEnabled(false);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cbLp.setMargins(0, 0, dp(4), 0);
        camToggleBtn.setLayoutParams(cbLp);
        ctrlRow.addView(camToggleBtn);

        hangupBtn = makeBtn("End", 0xFFB3261E, 0xFFFFFFFF);
        hangupBtn.setEnabled(false);
        ctrlRow.addView(hangupBtn, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(ctrlRow);
        setContentView(root);

        // Surface
        surfaceHolder = localPreview.getHolder();
        surfaceHolder.addCallback(this);
        try { surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); } catch (Exception ignored) {}

        // WakeLock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "x10call:wakelock");

        // Listeners
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
        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        camToggleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleCamera(); }
        });

        // Loudspeaker for video calls
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(true);
    }

    // ── HOST ──────────────────────────────────────────────────────────────────
    private void startHost() {
        roomCode = 100000 + new Random().nextInt(899999);
        setButtonsForCall(false);
        roomCodeText.setText("" + roomCode);
        setStatus("Starting...");
        serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    String myIp = getLocalIp();
                    if (myIp == null) { setStatusUI("No network."); setButtonsReady(); return; }
                    int port = 10000 + (roomCode % 50000);
                    serverSocket = new ServerSocket(port);
                    serverSocket.setSoTimeout(180000);
                    if (!postRendezvous(roomCode, myIp + ":" + port)) {
                        setStatusUI("Rendezvous failed."); setButtonsReady(); return;
                    }
                    setStatusUI("Code: " + roomCode);
                    connSocket = serverSocket.accept();
                    setupSocket(connSocket);
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

    // ── JOIN ──────────────────────────────────────────────────────────────────
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
                        setStatusUI("Waiting... (" + (i + 1) + ")");
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
                    setupSocket(connSocket);
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

    // Shared socket config after connect/accept
    private void setupSocket(Socket s) throws Exception {
        s.setTcpNoDelay(true);
        s.setSendBufferSize(16384);
        s.setReceiveBufferSize(16384);
        s.setKeepAlive(true);
        outStream = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 4096));
        inStream  = new DataInputStream(s.getInputStream());
    }

    // ── CONNECTED ─────────────────────────────────────────────────────────────
    private void onConnected() {
        connected     = true;
        hangingUp     = false;
        quotaBytes    = 0;
        callStartTime = System.currentTimeMillis();
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        uiHandler.post(new Runnable() {
            public void run() {
                hangupBtn.setEnabled(true);
                muteBtn.setEnabled(true);
                camToggleBtn.setEnabled(true);
                muteBtn.setText("Mute");
                muteBtn.setTextColor(COL_ON_SURF);
                camToggleBtn.setText("Cam off");
                camToggleBtn.setTextColor(COL_ON_SURF);
                statusText.setVisibility(View.GONE);
                roomCodeText.setText("");
                startTimerUpdater();
            }
        });
        startVideoSendThread();
        startRecvThread();
        startAudio();
    }

    // ── TIMER / QUOTA — updates every 2s to halve UI thread wakeups ──────────
    private void startTimerUpdater() {
        timerRunnable = new Runnable() {
            public void run() {
                if (!connected) return;
                long elapsed = System.currentTimeMillis() - callStartTime;
                long secs  = (elapsed / 1000) % 60;
                long mins  = (elapsed / 60000) % 60;
                long hours = elapsed / 3600000;
                String time = hours > 0
                    ? String.format("%d:%02d:%02d", hours, mins, secs)
                    : String.format("%02d:%02d", mins, secs);
                timerText.setText(time);
                quotaText.setText(String.format("%.2f MB", quotaBytes / 1048576f));
                uiHandler.postDelayed(this, 2000);
            }
        };
        uiHandler.post(timerRunnable);
    }

    // ── MUTE ──────────────────────────────────────────────────────────────────
    private void toggleMute() {
        micMuted = !micMuted;
        muteBtn.setText(micMuted ? "Unmute" : "Mute");
        muteBtn.setTextColor(micMuted ? COL_DANGER : COL_ON_SURF);
    }

    // ── CAMERA TOGGLE ─────────────────────────────────────────────────────────
    private void toggleCamera() {
        cameraOff = !cameraOff;
        camToggleBtn.setText(cameraOff ? "Cam on" : "Cam off");
        camToggleBtn.setTextColor(cameraOff ? COL_DANGER : COL_ON_SURF);
    }

    // ── VIDEO SEND ────────────────────────────────────────────────────────────
    private void startVideoSendThread() {
        videoSendThread = new Thread(new Runnable() {
            public void run() {
                while (connected) {
                    byte[] frame = null;
                    if (!cameraOff) {
                        synchronized (frameLock) {
                            if (pendingFrameJpeg != null) {
                                frame = pendingFrameJpeg;
                                pendingFrameJpeg = null;
                            } else {
                                // Wait for next frame rather than busy-spinning
                                try { frameLock.wait(FRAME_INTERVAL_MS); } catch (InterruptedException ignored) {}
                                continue;
                            }
                        }
                    } else {
                        try { Thread.sleep(FRAME_INTERVAL_MS); } catch (InterruptedException ignored) {}
                    }
                    if (frame != null) {
                        try {
                            synchronized (outLock) {
                                outStream.writeByte(PKT_VIDEO);
                                outStream.writeInt(frame.length);
                                outStream.write(frame);
                                outStream.flush();
                                quotaBytes += 5 + frame.length;
                            }
                        } catch (Exception e) {
                            if (connected) hangup();
                            break;
                        }
                    }
                }
            }
        });
        videoSendThread.setDaemon(true);
        videoSendThread.setPriority(Thread.NORM_PRIORITY);
        videoSendThread.start();
    }

    // ── RECV ──────────────────────────────────────────────────────────────────
    private void startRecvThread() {
        recvThread = new Thread(new Runnable() {
            public void run() {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565; // half the RAM of ARGB_8888
                while (connected) {
                    try {
                        int pktType = inStream.readByte() & 0xFF;
                        int len = inStream.readInt();
                        if (len <= 0 || len > 200000) continue;
                        byte[] buf = new byte[len];
                        inStream.readFully(buf);
                        quotaBytes += 5 + len;
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
    private void startAudio() {
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT);
        int recBuf = Math.max(minBuf, AUDIO_BUFFER_BYTES);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
        } catch (Exception e) {
            Log.w(TAG, "VOICE_COMMUNICATION unavailable, trying MIC");
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
            } catch (Exception e2) {
                Log.e(TAG, "AudioRecord unavailable: " + e2.getMessage());
                return;
            }
        }
        int playBuf = Math.max(
            AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT),
            AUDIO_BUFFER_BYTES);
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT, playBuf, AudioTrack.MODE_STREAM);

        audioRunning      = true;
        audioFlushCounter = 0;
        audioRecord.startRecording();
        audioTrack.play();

        audioSendThread = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[AUDIO_BUFFER_BYTES];
                while (connected && audioRunning) {
                    // Always read — keeps driver buffer drained even when muted
                    // (prevents audio stall / noise burst on unmute)
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0 && !micMuted) {
                        try {
                            synchronized (outLock) {
                                outStream.writeByte(PKT_AUDIO);
                                outStream.writeInt(read);
                                outStream.write(buf, 0, read);
                                quotaBytes += 5 + read;
                                // Batch flushes — BufferedOutputStream handles interim buffering
                                if (++audioFlushCounter >= FLUSH_EVERY) {
                                    outStream.flush();
                                    audioFlushCounter = 0;
                                }
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

    // ── CAMERA FRAME CALLBACK ─────────────────────────────────────────────────
    public void onPreviewFrame(byte[] data, Camera cam) {
        long now = System.currentTimeMillis();
        if (!connected || (now - lastFrameTime) < FRAME_INTERVAL_MS) {
            cam.addCallbackBuffer(data);
            return;
        }
        lastFrameTime = now;
        try {
            frameBuffer.reset(); // reuse — no allocation
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, FRAME_WIDTH, FRAME_HEIGHT, null);
            yuv.compressToJpeg(new Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT), JPEG_QUALITY, frameBuffer);
            synchronized (frameLock) {
                pendingFrameJpeg = frameBuffer.toByteArray();
                frameLock.notifyAll(); // wake video send thread
            }
        } catch (Exception e) {
            Log.w(TAG, "Frame error", e);
        }
        cam.addCallbackBuffer(data);
    }

    // ── HANG UP ───────────────────────────────────────────────────────────────
    private void hangup() {
        if (hangingUp) return; // guard against re-entrant calls from multiple threads
        hangingUp = true;
        connected = false;
        stopAudio();
        if (timerRunnable != null) { uiHandler.removeCallbacks(timerRunnable); timerRunnable = null; }
        synchronized (frameLock) { frameLock.notifyAll(); } // unblock video send wait()
        try { if (connSocket   != null) connSocket.close();   } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        micMuted  = false;
        cameraOff = false;
        setStatusUI("Call ended");
        setButtonsReady();
        uiHandler.post(new Runnable() {
            public void run() {
                remoteView.setImageBitmap(null);
                statusText.setVisibility(View.VISIBLE);
                roomCodeText.setText("");
                muteBtn.setEnabled(false);
                camToggleBtn.setEnabled(false);
                muteBtn.setText("Mute");
                muteBtn.setTextColor(COL_ON_SURF);
                camToggleBtn.setText("Cam off");
                camToggleBtn.setTextColor(COL_ON_SURF);
                timerText.setText("");
                quotaText.setText("");
            }
        });
    }

    // ── UI HELPERS ────────────────────────────────────────────────────────────
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
        muteBtn.setEnabled(!enabled);
        camToggleBtn.setEnabled(!enabled);
    }
    private void setButtonsReady() {
        uiHandler.post(new Runnable() {
            public void run() {
                hostBtn.setEnabled(true);
                joinBtn.setEnabled(true);
                roomInput.setEnabled(true);
                hangupBtn.setEnabled(false);
                muteBtn.setEnabled(false);
                camToggleBtn.setEnabled(false);
            }
        });
    }

    // ── CAMERA OPEN ───────────────────────────────────────────────────────────
    private void openCamera(SurfaceHolder holder) {
        if (cameraRunning || holder == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 9) {
                int numCams = Camera.getNumberOfCameras();
                Camera.CameraInfo info = new Camera.CameraInfo();
                int frontId = -1, backId = -1;
                for (int i = 0; i < numCams; i++) {
                    Camera.getCameraInfo(i, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && frontId == -1) frontId = i;
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK  && backId  == -1) backId  = i;
                }
                if      (frontId != -1) camera = Camera.open(frontId);
                else if (backId  != -1) camera = Camera.open(backId);
                else if (numCams  >  0) camera = Camera.open(0);
            } else {
                camera = Camera.open();
            }
            if (camera == null) { setStatus("No camera."); return; }

            Camera.Parameters p = camera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = sizes.get(0);
            int bestDiff = Math.abs(best.width - FRAME_WIDTH) + Math.abs(best.height - FRAME_HEIGHT);
            for (Camera.Size s : sizes) {
                int diff = Math.abs(s.width - FRAME_WIDTH) + Math.abs(s.height - FRAME_HEIGHT);
                if (diff < bestDiff) { best = s; bestDiff = diff; }
            }
            p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            try {
                List<int[]> fpsRanges = p.getSupportedPreviewFpsRange();
                if (fpsRanges != null && !fpsRanges.isEmpty()) {
                    int[] lowest = fpsRanges.get(0);
                    for (int[] r : fpsRanges) { if (r[1] < lowest[1]) lowest = r; }
                    p.setPreviewFpsRange(lowest[0], lowest[1]);
                }
            } catch (Exception ignored) {}
            try { p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);   } catch (Exception ignored) {}
            try { p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED); } catch (Exception ignored) {}
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
            Log.e(TAG, "Camera open error", e);
            setStatus("Camera error: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); camera.stopPreview(); camera.release(); }
            catch (Exception ignored) {}
            camera = null;
            cameraRunning = false;
        }
    }

    // ── SURFACE CALLBACKS ─────────────────────────────────────────────────────
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        uiHandler.postDelayed(new Runnable() {
            public void run() { openCamera(surfaceHolder); }
        }, 400);
    }
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (camera != null) {
            try { camera.stopPreview(); camera.setPreviewDisplay(holder); camera.startPreview(); }
            catch (Exception ignored) {}
        }
    }
    public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }

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
        } catch (Exception e) {
            Log.e(TAG, "Post error", e);
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
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            byte[] tmp = new byte[512];
            int n;
            while ((n = is.read(tmp)) != -1) baos.write(tmp, 0, n);
            String response = baos.toString("UTF-8").trim();
            int msgIdx = response.lastIndexOf("\"message\":\"");
            if (msgIdx == -1) return null;
            int start = msgIdx + 11;
            int end   = response.indexOf("\"", start);
            if (end == -1) return null;
            return response.substring(start, end);
        } catch (Exception e) {
            Log.e(TAG, "Get error", e);
            return null;
        } finally { if (conn != null) conn.disconnect(); }
    }

    // ── IP DETECTION ──────────────────────────────────────────────────────────
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

    // ── LIFECYCLE ─────────────────────────────────────────────────────────────
    @Override protected void onDestroy() { super.onDestroy(); hangup(); closeCamera(); }
    @Override protected void onPause()   { super.onPause();   if (!connected) closeCamera(); }
    @Override protected void onResume()  {
        super.onResume();
        if (!cameraRunning && surfaceHolder != null) {
            uiHandler.postDelayed(new Runnable() {
                public void run() { openCamera(surfaceHolder); }
            }, 400);
        }
    }
}
