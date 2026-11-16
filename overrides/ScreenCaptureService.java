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
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gestisce la sessione e pubblica sempre lo stato effettivo nelle notifiche e nell'Activity. */
public class ScreenCaptureService extends Service {
    public static final String ACTION_START = "it.example.registraschermo.START";
    public static final String ACTION_STOP = "it.example.registraschermo.STOP";
    public static final String ACTION_PAUSE = "it.example.registraschermo.PAUSE";
    public static final String ACTION_RESUME = "it.example.registraschermo.RESUME";
    public static final String ACTION_STATUS = "it.example.registraschermo.STATUS";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_RECORDING = "RECORDING";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_SAVING = "SAVING";

    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 101;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private AvRecorder recorder;
    private volatile String state = STATE_IDLE;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            worker.execute(this::finishRecording);
        } else if (ACTION_PAUSE.equals(action)) {
            worker.execute(() -> changePause(true));
        } else if (ACTION_RESUME.equals(action)) {
            worker.execute(() -> changePause(false));
        } else if (ACTION_START.equals(action) && STATE_IDLE.equals(state)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                publish(STATE_IDLE, "Autorizzazione alla registrazione mancante.");
                stopSelf();
                return START_NOT_STICKY;
            }
            createChannel();
            state = STATE_STARTING;
            startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            publish(STATE_STARTING, "Preparazione della registrazione...");
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
            publish(STATE_RECORDING, "REC - Registrazione attiva. Video e audio in acquisizione.");
        } catch (Exception exception) {
            if (recorder != null) recorder.abort();
            recorder = null;
            publish(STATE_IDLE, "Errore di avvio: " + exception.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /** Una pressione sulla notifica cambia stato soltanto se è realmente valida. */
    private void changePause(boolean shouldPause) {
        if (finishing.get() || recorder == null) return;
        if (shouldPause && !STATE_RECORDING.equals(state)) return;
        if (!shouldPause && !STATE_PAUSED.equals(state)) return;
        try {
            recorder.setPaused(shouldPause);
            if (recorder.isPaused() != shouldPause) {
                throw new IllegalStateException("Il codec non ha confermato il cambio di stato");
            }
            publish(shouldPause ? STATE_PAUSED : STATE_RECORDING,
                    shouldPause
                            ? "IN PAUSA - Video e audio sospesi. Tocca Riprendi."
                            : "REC - Registrazione ripresa. Video e audio in acquisizione.");
        } catch (Exception error) {
            publish(state, "Impossibile cambiare pausa: " + error.getMessage());
        }
    }

    private void finishRecording() {
        if (!finishing.compareAndSet(false, true)) return;
        try {
            if (recorder != null) {
                publish(STATE_SAVING, "STOP - Salvataggio del video in corso...");
                Uri uri = recorder.finishAndSave();
                publish(STATE_IDLE, "Video salvato in Filmati/RegistraSchermo.");
            } else {
                publish(STATE_IDLE, "Registrazione fermata.");
            }
        } catch (Exception error) {
            publish(STATE_IDLE, "Errore durante il salvataggio: " + error.getMessage());
        } finally {
            recorder = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void publish(String newState, String message) {
        state = newState;
        boolean active = !STATE_IDLE.equals(newState);
        getSharedPreferences("recording", MODE_PRIVATE).edit()
                .putString("state", newState)
                .putBoolean("active", active)
                .putBoolean("paused", STATE_PAUSED.equals(newState))
                .putString("status", message)
                .apply();
        Intent broadcast = new Intent(ACTION_STATUS).setPackage(getPackageName());
        sendBroadcast(broadcast);
        if (active) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification());
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Registrazione dello schermo", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Stato REC / PAUSA e pulsanti Riprendi e Stop");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private PendingIntent command(String action, int code) {
        Intent intent = new Intent(this, ScreenCaptureService.class).setAction(action);
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean paused = STATE_PAUSED.equals(state);
        String title = paused ? "IN PAUSA - registrazione sospesa"
                : STATE_SAVING.equals(state) ? "STOP - Salvataggio video"
                : STATE_STARTING.equals(state) ? "Preparazione registrazione..."
                : "REC - Registrazione in corso";
        String details = paused ? "Video e audio in pausa. Premi Riprendi per continuare."
                : STATE_SAVING.equals(state) ? "Creazione del file MP4..."
                : STATE_STARTING.equals(state) ? "Avvio del video e audio interno..."
                : "Video e audio interno attivi; microfono escluso.";

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(title)
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (STATE_RECORDING.equals(state)) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Pausa", command(ACTION_PAUSE, 10)).build());
        } else if (paused) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_play, "Riprendi", command(ACTION_RESUME, 11)).build());
        }
        if (!STATE_SAVING.equals(state)) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Stop e salva", command(ACTION_STOP, 12)).build());
        }
        return builder.build();
    }

    @Override public void onDestroy() {
        // Evita che l'Activity resti bloccata su REC se Android chiude il servizio.
        if (!STATE_IDLE.equals(state)) publish(STATE_IDLE, "Registrazione interrotta.");
        worker.shutdown();
        super.onDestroy();
    }
}