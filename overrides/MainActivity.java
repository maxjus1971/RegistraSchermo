package it.example.registraschermo;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 10;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 11;
    private static final int PROJECTION_REQUEST = 12;
    private static final int OVERLAY_REQUEST = 13;
    private boolean waitingOverlay;

    private Button startButton;
    private Button stopButton;
    private Button pauseButton;
    private TextView statusText;
    private MediaProjectionManager projectionManager;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateStatus();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        pauseButton = findViewById(R.id.pauseButton);
        statusText = findViewById(R.id.statusText);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        startButton.setOnClickListener(v -> prepareRecording());
        pauseButton.setOnClickListener(v -> {
            boolean paused = getSharedPreferences("recording", MODE_PRIVATE).getBoolean("paused", false);
            Intent command = new Intent(this, ScreenCaptureService.class);
            command.setAction(paused ? ScreenCaptureService.ACTION_RESUME : ScreenCaptureService.ACTION_PAUSE);
            startService(command);
        });
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, ScreenCaptureService.class);
            stop.setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
            statusText.setText("Salvataggio del video in corso...");
        });
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ScreenCaptureService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
            receiverRegistered = true;
        }
        updateStatus();
        if (waitingOverlay && Settings.canDrawOverlays(this)) {
            waitingOverlay = false;
            requestProjection();
        }
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void updateStatus() {
        boolean active = getSharedPreferences("recording", MODE_PRIVATE)
                .getBoolean("active", false);
        String status = getSharedPreferences("recording", MODE_PRIVATE)
                .getString("status", "Pronto per registrare");
        boolean paused = getSharedPreferences("recording", MODE_PRIVATE)
                .getBoolean("paused", false);
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        pauseButton.setEnabled(active);
        pauseButton.setText(paused ? "Riprendi" : "Pausa");
        statusText.setText(status);
    }

    private void prepareRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
            return;
        }
        requestNotificationPermissionOrProjection();
    }

    private void requestNotificationPermissionOrProjection() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        requestOverlayPermissionOrProjection();
    }

    private void requestOverlayPermissionOrProjection() {
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlay = true;
            statusText.setText("Autorizza Mostra sopra altre app per i comandi flottanti.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(settings, OVERLAY_REQUEST);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), PROJECTION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionOrProjection();
            } else {
                statusText.setText("Il permesso audio serve per registrare l'audio interno. Il microfono non viene usato.");
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // Permesso notifiche facoltativo, ma i controlli richiedono quello overlay.
            requestOverlayPermissionOrProjection();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQUEST) {
            // La finestra delle impostazioni può essere chiusa prima o dopo onResume.
            if (waitingOverlay && Settings.canDrawOverlays(this)) {
                waitingOverlay = false;
                requestProjection();
            } else if (!Settings.canDrawOverlays(this)) {
                statusText.setText("Attiva il permesso per mostrare i controlli flottanti.");
            }
            return;
        }
        if (requestCode != PROJECTION_REQUEST) return;
        if (resultCode != RESULT_OK || data == null) {
            statusText.setText("Registrazione annullata.");
            return;
        }
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.setAction(ScreenCaptureService.ACTION_START);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        statusText.setText("Avvio della registrazione...");
        // La registrazione continua anche mentre si usano altre applicazioni.
    }
}