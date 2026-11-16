package it.example.registraschermo;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 10;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 11;
    private static final int PROJECTION_REQUEST = 12;

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
            String state = getSharedPreferences("recording", MODE_PRIVATE)
                    .getString("state", ScreenCaptureService.STATE_IDLE);
            Intent command = new Intent(this, ScreenCaptureService.class);
            if (ScreenCaptureService.STATE_PAUSED.equals(state)) {
                command.setAction(ScreenCaptureService.ACTION_RESUME);
            } else if (ScreenCaptureService.STATE_RECORDING.equals(state)) {
                command.setAction(ScreenCaptureService.ACTION_PAUSE);
            } else return;
            pauseButton.setEnabled(false); // Evita il doppio tocco durante l'elaborazione.
            startService(command);
        });
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, ScreenCaptureService.class);
            stop.setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
            startButton.setEnabled(false);
            pauseButton.setEnabled(false);
            stopButton.setEnabled(false);
            statusText.setText("STOP - Salvataggio in corso...");
            statusText.setTextColor(0xFF93C5FD);
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
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void updateStatus() {
        String state = getSharedPreferences("recording", MODE_PRIVATE)
                .getString("state", ScreenCaptureService.STATE_IDLE);
        String lastMessage = getSharedPreferences("recording", MODE_PRIVATE)
                .getString("status", "Pronto per registrare");
        boolean recording = ScreenCaptureService.STATE_RECORDING.equals(state);
        boolean paused = ScreenCaptureService.STATE_PAUSED.equals(state);
        boolean idle = ScreenCaptureService.STATE_IDLE.equals(state);

        startButton.setEnabled(idle);
        pauseButton.setEnabled(recording || paused);
        stopButton.setEnabled(recording || paused);
        pauseButton.setText(paused ? "Riprendi registrazione" : "Pausa");
        startButton.setText("Avvia nuova registrazione");

        if (recording) {
            statusText.setText("● REC - REGISTRAZIONE ATTIVA\nVideo e audio interno in acquisizione");
            statusText.setTextColor(0xFFFB7185);
        } else if (paused) {
            statusText.setText("Ⅱ IN PAUSA\nRegistrazione sospesa - premi Riprendi");
            statusText.setTextColor(0xFFFBBF24);
        } else if (ScreenCaptureService.STATE_STARTING.equals(state)) {
            statusText.setText("Avvio della registrazione...");
            statusText.setTextColor(0xFF93C5FD);
        } else if (ScreenCaptureService.STATE_SAVING.equals(state)) {
            statusText.setText("STOP - Salvataggio del video in corso...");
            statusText.setTextColor(0xFF93C5FD);
        } else {
            statusText.setText(lastMessage);
            statusText.setTextColor(Color.WHITE);
        }
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestProjection();
            } else {
                statusText.setText("Autorizza le notifiche di RegistraSchermo nelle impostazioni Android per vedere REC / PAUSA e usare Stop dalla tendina.");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        startButton.setEnabled(false);
        statusText.setText("Preparazione della registrazione...");
    }
}