package com.x10call;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
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
    private static final int FRAME_WIDTH        = 320;
    private static final int FRAME_HEIGHT       = 240;
    private static final int JPEG_QUALITY       = 35;
    private static final int TARGET_FPS         = 12;
    private static final long FRAME_INTERVAL_MS = 1000 / TARGET_FPS;

    // Audio settings
    private static final int  AUDIO_SAMPLE_RATE  = 8000;
    private static final int  AUDIO_CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO;
    private static final int  AUDIO_CHANNEL_OUT  = AudioFormat.CHANNEL_OUT_MONO;
    private static final int  AUDIO_FORMAT       = AudioFormat.ENCODING_PCM_16BIT;
    private static final int  AUDIO_BUFFER_MS    = 160;
    private static final int  AUDIO_BUFFER_BYTES = AUDIO_SAMPLE_RATE * 2 * AUDIO_BUFFER_MS / 1000;

    // Packet types
    private static final int PKT_VIDEO = 1;
    private static final int PKT_AUDIO = 2;

    // ── PRE-CALL UI ───────────────────────────────────────────────────────────
    private View         preCallLayout;   // the entire pre-call screen
    private SurfaceView  localPreview;
    private TextView     statusText;
    private TextView     roomCodeText;
    private EditText     roomInput;
    private Button       hostBtn;
    private Button       joinBtn;

    // ── IN-CALL UI ────────────────────────────────────────────────────────────
    private View         inCallLayout;   // full-screen call view
    private ImageView    remoteView;
    private TextView     callStatsText;  // elapsed time + bytes, top-right overlay
    private View         menuAnchor;     // the ⋮ button bottom-right

    // ── Typeface (loaded once) ────────────────────────────────────────────────
    private Typeface robotoLight;
    private Typeface robotoRegular;

    // ── Camera ────────────────────────────────────────────────────────────────
    private Camera        camera;
    private SurfaceHolder surfaceHolder;
    private boolean       cameraRunning = false;
    private byte[]        yuvBuffer;

    // ── Audio ─────────────────────────────────────────────────────────────────
    private AudioRecord  audioRecord;
    private AudioTrack   audioTrack;
    private Thread       audioSendThread;
    private boolean      audioRunning = false;
    private boolean      micMuted     = false;

    // ── Camera toggle ─────────────────────────────────────────────────────────
    private boolean      cameraMuted  = false;   // true = sending black frames

    // ── Video rotation ────────────────────────────────────────────────────────
    // Tracks rotation applied to received video (0 / 90 / 180 / 270)
    private int          remoteRotation = 0;

    // ── Network ───────────────────────────────────────────────────────────────
    private ServerSocket     serverSocket;
    private Socket           connSocket;
    private DataOutputStream outStream;
    private DataInputStream  inStream;
    private boolean          connected = false;
    private int              roomCode  = 0;

    // ── Threading ─────────────────────────────────────────────────────────────
    private Handler uiHandler    = new Handler();
    private Thread  videoSendThread, recvThread, serverThread, connectThread;

    // ── Frame buffer ──────────────────────────────────────────────────────────
    private final Object frameLock        = new Object();
    private byte[]       pendingFrameJpeg = null;
    private long         lastSendTime     = 0;

    // ── Output lock ───────────────────────────────────────────────────────────
    private final Object outLock = new Object();

    // ── WakeLock ──────────────────────────────────────────────────────────────
    private PowerManager.WakeLock wakeLock;

    // ── Stats ─────────────────────────────────────────────────────────────────
    private long callStartMs   = 0;
    private long bytesReceived = 0;
    private Runnable statsRunnable;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Full-screen, no title bar
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        setContentView(R.layout.main);

        // Load Roboto from assets — zero overhead, API 2.1 compatible
        try {
            robotoLight   = Typeface.createFromAsset(getAssets(), "fonts/Roboto-Light.ttf");
            robotoRegular = Typeface.createFromAsset(getAssets(), "fonts/Roboto-Regular.ttf");
        } catch (Exception e) {
            Log.w(TAG, "Roboto font not found — using system font. Add fonts/Roboto-Regular.ttf and fonts/Roboto-Light.ttf to assets/");
            robotoLight   = Typeface.DEFAULT;
            robotoRegular = Typeface.DEFAULT;
        }

        // ── Wire up pre-call UI ───────────────────────────────────────────────
        preCallLayout = findViewById(R.id.preCallLayout);
        localPreview  = (SurfaceView) findViewById(R.id.localPreview);
        statusText    = (TextView)    findViewById(R.id.statusText);
        roomCodeText  = (TextView)    findViewById(R.id.roomCodeText);
        roomInput     = (EditText)    findViewById(R.id.roomInput);
        hostBtn       = (Button)      findViewById(R.id.hostBtn);
        joinBtn       = (Button)      findViewById(R.id.joinBtn);

        applyFont(statusText,   robotoLight);
        applyFont(roomCodeText, robotoRegular);
        applyFont(roomInput,    robotoLight);
        applyFont(hostBtn,      robotoRegular);
        applyFont(joinBtn,      robotoRegular);

        // ── Wire up in-call UI ────────────────────────────────────────────────
        inCallLayout  = findViewById(R.id.inCallLayout);
        remoteView    = (ImageView) findViewById(R.id.remoteView);
        callStatsText = (TextView)  findViewById(R.id.callStatsText);
        menuAnchor    = findViewById(R.id.menuAnchor);

        applyFont(callStatsText, robotoLight);

        // remoteView fills screen — scale type CENTER_CROP to fill every pixel
        remoteView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        surfaceHolder = localPreview.getHolder();
        surfaceHolder.addCallback(this);
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

        // ⋮ menu button — shows hidden controls during call
        menuAnchor.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showCallMenu(v); }
        });

        // ── FIX 1: Route audio to SPEAKER immediately and keep it there ───────
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);      // NOT MODE_IN_CALL / MODE_IN_COMMUNICATION
        am.setSpeakerphoneOn(true);                // speaker on by default

        showPreCall();
        setStatus("Ready");
    }

    // ── SHOW / HIDE SCREENS ───────────────────────────────────────────────────
    private void showPreCall() {
        preCallLayout.setVisibility(View.VISIBLE);
        inCallLayout.setVisibility(View.GONE);
        stopStatsUpdater();
    }

    private void showInCall() {
        preCallLayout.setVisibility(View.GONE);
        inCallLayout.setVisibility(View.VISIBLE);
        // Shrink local preview to a small pip in the corner — or hide if camera is off
        localPreview.setVisibility(View.VISIBLE);
        callStartMs = SystemClock.elapsedRealtime();
        bytesReceived = 0;
        startStatsUpdater();
    }

    // ── CALL STATS OVERLAY (time + data) ─────────────────────────────────────
    private void startStatsUpdater() {
        statsRunnable = new Runnable() {
            public void run() {
                if (!connected) return;
                long elapsed = (SystemClock.elapsedRealtime() - callStartMs) / 1000;
                long mins = elapsed / 60;
                long secs = elapsed % 60;
                String time = String.format("%d:%02d", mins, secs);
                String data = formatBytes(bytesReceived);
                callStatsText.setText(time + " · " + data);
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.post(statsRunnable);
    }

    private void stopStatsUpdater() {
        if (statsRunnable != null) {
            uiHandler.removeCallbacks(statsRunnable);
            statsRunnable = null;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024)           return bytes + "B";
        if (bytes < 1024 * 1024)    return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024f * 1024f));
    }

    // ── HIDDEN CALL MENU (PopupWindow — legacy Android compatible) ────────────
    private void showCallMenu(View anchor) {
        // Build menu layout entirely in code — no extra XML needed
        LinearLayout menuLayout = new LinearLayout(this);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setBackgroundResource(R.drawable.menu_bg);    // rounded rect drawable
        menuLayout.setPadding(dp(8), dp(8), dp(8), dp(8));

        final PopupWindow popup = new PopupWindow(
            menuLayout,
            dp(160),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true  // focusable — dismisses on outside touch
        );

        TextView muteBtn    = makeMenuButton(micMuted    ? "Unmute Mic"    : "Mute Mic");
        TextView camBtn     = makeMenuButton(cameraMuted ? "Camera On"     : "Camera Off");
        TextView rotateBtn  = makeMenuButton("Rotate Video");
        TextView hangupBtn  = makeMenuButton("End Call");
        hangupBtn.setTextColor(0xFFE53935); // red for hang up

        menuLayout.addView(muteBtn);
        menuLayout.addView(makeDivider());
        menuLayout.addView(camBtn);
        menuLayout.addView(makeDivider());
        menuLayout.addView(rotateBtn);
        menuLayout.addView(makeDivider());
        menuLayout.addView(hangupBtn);

        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                micMuted = !micMuted;
                popup.dismiss();
            }
        });
        camBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cameraMuted = !cameraMuted;
                localPreview.setVisibility(cameraMuted ? View.INVISIBLE : View.VISIBLE);
                popup.dismiss();
            }
        });
        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                remoteRotation = (remoteRotation + 90) % 360;
                popup.dismiss();
            }
        });
        hangupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                popup.dismiss();
                hangup();
            }
        });

        // Show above the anchor, aligned to its right edge
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        popup.showAsDropDown(anchor, 0, -dp(200));
    }

    private TextView makeMenuButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTypeface(robotoRegular);
        tv.setTextSize(15);
        tv.setTextColor(0xFFFFFFFF);
        tv.setPadding(dp(12), dp(14), dp(12), dp(14));
        tv.setBackgroundResource(R.drawable.menu_item_selector); // pressed state
        return tv;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 1));
        v.setBackgroundColor(0x33FFFFFF);
        return v;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyFont(TextView tv, Typeface tf) {
        if (tv != null && tf != null) tv.setTypeface(tf);
    }

    // ── HOST ──────────────────────────────────────────────────────────────────
    private void startHost() {
        roomCode = 100000 + new Random().nextInt(899999);
        hostBtn.setEnabled(false);
        joinBtn.setEnabled(false);
        roomInput.setEnabled(false);
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

    // ── CONNECTED ─────────────────────────────────────────────────────────────
    private void onConnected() {
        connected = true;
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();

        // ── FIX 1: Force speaker AFTER connection established ─────────────────
        // Must happen on a thread-safe call to AudioManager — use uiHandler
        uiHandler.post(new Runnable() {
            public void run() {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                // MODE_NORMAL lets AudioTrack stream to STREAM_MUSIC → big speaker
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
                        if (pendingFrameJpeg != null) {
                            frame = pendingFrameJpeg;
                            pendingFrameJpeg = null;
                        }
                    }
                    if (frame != null) {
                        // If camera is muted, replace with a tiny black JPEG
                        if (cameraMuted) frame = getBlackFrame();
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

    // Cached black frame so we don't allocate on every send when cam is off
    private byte[] blackFrameCache = null;
    private byte[] getBlackFrame() {
        if (blackFrameCache == null) {
            Bitmap b = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.RGB_565);
            b.eraseColor(0xFF000000);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            b.compress(Bitmap.CompressFormat.JPEG, 20, baos);
            b.recycle();
            blackFrameCache = baos.toByteArray();
        }
        return blackFrameCache;
    }

    // ── UNIFIED RECV ──────────────────────────────────────────────────────────
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
                                // Apply rotation if user requested it
                                final Bitmap displayBmp = applyRotation(bmp, remoteRotation);
                                uiHandler.post(new Runnable() {
                                    public void run() {
                                        remoteView.setImageBitmap(displayBmp);
                                    }
                                });
                            }
                        } else if (pktType == PKT_AUDIO) {
                            if (audioTrack != null && audioRunning) {
                                // If mic is muted on our end we still play their audio
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

    // Rotate bitmap using Matrix — no heavyweight library needed
    private Bitmap applyRotation(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
        src.recycle();
        return rotated;
    }

    // ── AUDIO ─────────────────────────────────────────────────────────────────
    // ── FIX 1 CORE: Use STREAM_MUSIC instead of STREAM_VOICE_CALL ─────────────
    // STREAM_VOICE_CALL is hard-wired to the earpiece on most Android 2.x devices.
    // STREAM_MUSIC routes through the media path → main loudspeaker.
    private void startAudio() {
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT);
        int recBuf = Math.max(minBuf, AUDIO_BUFFER_BYTES);

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_IN, AUDIO_FORMAT, recBuf);
        } catch (Exception e) {
            Log.w(TAG, "AudioRecord VOICE_COMMUNICATION failed, trying MIC");
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

        // *** KEY CHANGE: STREAM_MUSIC → routes to loudspeaker ***
        audioTrack = new AudioTrack(
            AudioManager.STREAM_MUSIC,          // was STREAM_VOICE_CALL
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_OUT, AUDIO_FORMAT,
            playBuf, AudioTrack.MODE_STREAM);

        audioRunning = true;
        audioRecord.startRecording();
        audioTrack.play();

        audioSendThread = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[AUDIO_BUFFER_BYTES];
                while (connected && audioRunning) {
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0 && !micMuted) {  // honour mute flag
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
                    } else if (read > 0 && micMuted) {
                        // Send silence so the other side doesn't stall
                        try {
                            byte[] silence = new byte[read]; // zero-filled
                            synchronized (outLock) {
                                outStream.writeByte(PKT_AUDIO);
                                outStream.writeInt(read);
                                outStream.write(silence, 0, read);
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
        try { if (connSocket   != null) connSocket.close();   } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        // Reset audio mode
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setSpeakerphoneOn(false);
        am.setMode(AudioManager.MODE_NORMAL);

        setStatusUI("Call ended");
        setButtonsReady();
        uiHandler.post(new Runnable() {
            public void run() {
                remoteView.setImageBitmap(null);
                showPreCall();
                roomCodeText.setText("");
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
    private void setButtonsReady() {
        uiHandler.post(new Runnable() {
            public void run() {
                hostBtn.setEnabled(true);
                joinBtn.setEnabled(true);
                roomInput.setEnabled(true);
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

    // ── CAMERA LIFECYCLE ──────────────────────────────────────────────────────
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
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
            if (Build.VERSION.SDK_INT >= 9) {
                int numCams = Camera.getNumberOfCameras();
                Camera.CameraInfo info = new Camera.CameraInfo();
                for (int i = 0; i < numCams; i++) {
                    Camera.getCameraInfo(i, info);
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
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
            cameraRunning = false;
        }
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
