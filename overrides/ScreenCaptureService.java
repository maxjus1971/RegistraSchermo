package it.example.registraschermo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START = "it.example.registraschermo.START";
    public static final String ACTION_STOP = "it.example.registraschermo.STOP";
    public static final String ACTION_PAUSE = "it.example.registraschermo.PAUSE";
    public static final String ACTION_RESUME = "it.example.registraschermo.RESUME";
    public static final String ACTION_STATUS = "it.example.registraschermo.STATUS";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 101;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AvRecorder recorder;
    private FloatingControls controls;
    private volatile boolean starting;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            worker.execute(this::finishRecording);
        } else if (ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action)) {
            boolean pause = ACTION_PAUSE.equals(action);
            worker.execute(() -> changePause(pause));
        } else if (ACTION_START.equals(action) && !starting && recorder == null) {
            starting = true;
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                changeState(false, "Autorizzazione alla registrazione mancante.");
                stopSelf();
                return START_NOT_STICKY;
            }
            createChannel();
            Notification notification = notification();
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            changeState(true, "Registrazione in corso. Apri l'app desiderata.");
            worker.execute(() -> startRecording(resultCode, resultData));
        }
        return START_NOT_STICKY;
    }

    private void startRecording(int resultCode, Intent data) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            MediaProjection projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("Autorizzazione schermo non valida");
            recorder = new AvRecorder(this, projection,
                    () -> worker.execute(this::finishRecording));
            recorder.start();
            starting = false;
            mainHandler.post(() -> {
                if (recorder != null) {
                    controls = new FloatingControls(this);
                    controls.show();
                }
            });
        } catch (Exception exception) {
            starting = false;
            if (recorder != null) recorder.abort();
            recorder = null;
            changeState(false, "Errore di avvio: " + exception.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void changePause(boolean paused) {
        if (finishing.get() || recorder == null) return;
        try {
            recorder.setPaused(paused);
            changeState(true, paused ? "In pausa. Tocca Riprendi." : "Registrazione in corso.");
            mainHandler.post(() -> {
                if (controls != null) controls.update(paused);
            });
        } catch (Exception error) {
            changeState(true, "Impossibile cambiare pausa: " + error.getMessage());
        }
    }

    private void finishRecording() {
        if (!finishing.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            if (controls != null) controls.hide();
            controls = null;
        });
        try {
            if (recorder != null) {
                changeState(true, "Salvataggio del video...");
                Uri uri = recorder.finishAndSave();
                changeState(false, "Video salvato in Filmati/RegistraSchermo.");
            } else {
                changeState(false, "Registrazione fermata.");
            }
        } catch (Exception error) {
            changeState(false, "Errore durante il salvataggio: " + error.getMessage());
        } finally {
            recorder = null;
            starting = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void changeState(boolean active, String message) {
        getSharedPreferences("recording", MODE_PRIVATE).edit()
                .putBoolean("active", active)
                .putBoolean("paused", active && recorder != null && recorder.isPaused())
                .putString("status", message)
                .apply();
        Intent broadcast = new Intent(ACTION_STATUS);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Registrazione dello schermo", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mostra quando è in corso la registrazione dello schermo");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("Registrazione schermo attiva")
                .setContentText("Video e audio interno, senza microfono")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Ferma e salva", stopIntent)
                .build();
    }

    @Override public void onDestroy() {
        // In caso di arresto forzato non si promette un file valido.
        if (controls != null) controls.hide();
        controls = null;
        worker.shutdown();
        super.onDestroy();
    }
}