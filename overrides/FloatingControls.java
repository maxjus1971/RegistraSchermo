package it.example.registraschermo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Barra di registrazione trascinabile. FLAG_SECURE evita che i controlli siano
 * ripresi da MediaProjection. Su alcuni firmware la zona protetta può apparire
 * oscurata: il comportamento dipende dal compositore del dispositivo. */
final class FloatingControls {
    private final ScreenCaptureService service;
    private WindowManager windowManager;
    private LinearLayout root;
    private WindowManager.LayoutParams params;
    private TextView record, pause, stop;
    private boolean isPaused;

    FloatingControls(ScreenCaptureService service) {
        this.service = service;
    }

    void show() {
        if (root != null || !android.provider.Settings.canDrawOverlays(service)) return;
        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        root = new LinearLayout(service);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.setBackground(round(Color.argb(232, 15, 23, 42), 17));
        root.setElevation(dp(10));

        TextView drag = label("⋮⋮", Color.WHITE, 16);
        drag.setContentDescription("Trascina i comandi");
        drag.setPadding(dp(8), dp(9), dp(8), dp(9));
        root.addView(drag);

        record = label("● REC", Color.WHITE, 13);
        record.setBackground(round(Color.rgb(189, 28, 47), 11));
        record.setContentDescription("Registrazione in corso");
        record.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(record, margin(3));

        pause = label("Ⅱ Pausa", Color.WHITE, 13);
        pause.setBackground(round(Color.rgb(51, 65, 85), 11));
        pause.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(pause, margin(4));

        stop = label("■ Stop", Color.WHITE, 13);
        stop.setBackground(round(Color.rgb(51, 65, 85), 11));
        stop.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(stop, margin(4));

        pause.setOnClickListener(v -> dispatch(isPaused
                ? ScreenCaptureService.ACTION_RESUME : ScreenCaptureService.ACTION_PAUSE));
        record.setOnClickListener(v -> {
            if (isPaused) dispatch(ScreenCaptureService.ACTION_RESUME);
        });
        stop.setOnClickListener(v -> dispatch(ScreenCaptureService.ACTION_STOP));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SECURE,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.x = dp(10);
        params.y = dp(105);

        drag.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    params.x = Math.max(0, params.x - Math.round(event.getRawX() - lastX));
                    params.y = Math.max(0, params.y + Math.round(event.getRawY() - lastY));
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    try { windowManager.updateViewLayout(root, params); } catch (Exception ignored) { }
                    return true;
                }
                return true;
            }
        });

        try {
            windowManager.addView(root, params);
            update(false);
        } catch (Exception error) {
            root = null;
        }
    }

    void update(boolean paused) {
        isPaused = paused;
        if (pause == null || record == null) return;
        pause.setText(paused ? "▶ Riprendi" : "Ⅱ Pausa");
        record.setText(paused ? "Ⅱ PAUSA" : "● REC");
        record.setContentDescription(paused ? "Riprendi la registrazione" : "Registrazione in corso");
        record.setBackground(round(paused ? Color.rgb(100, 116, 139) : Color.rgb(189, 28, 47), 11));
    }

    void hide() {
        if (root != null && windowManager != null) {
            try { windowManager.removeView(root); } catch (Exception ignored) { }
        }
        root = null;
        pause = null;
        record = null;
        stop = null;
        windowManager = null;
    }

    private void dispatch(String action) {
        Intent i = new Intent(service, ScreenCaptureService.class);
        i.setAction(action);
        service.startService(i);
    }

    private TextView label(String text, int color, int size) {
        TextView view = new TextView(service);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams margin(int leftDp) {
        LinearLayout.LayoutParams out = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        out.leftMargin = dp(leftDp);
        return out;
    }

    private int dp(float size) {
        return (int) (service.getResources().getDisplayMetrics().density * size + 0.5f);
    }
}