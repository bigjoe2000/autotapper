package com.autotapper.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GameAutomationService extends AccessibilityService {

    public static volatile GameAutomationService instance;
    public volatile boolean isOverlayVisible = false;

    private WindowManager windowManager;
    private View floatingMenu;
    private WindowManager.LayoutParams menuParams;

    private final List<TapTarget> tapTargets = new ArrayList<>();
    private final List<View> pinViews = new ArrayList<>();
    private final List<WindowManager.LayoutParams> pinParamsList = new ArrayList<>();

    private boolean isRunning = false;
    private Runnable nextTapRunnable;

    private ImageButton btnAdd;
    private ImageButton btnPlay;
    private ImageButton btnSettings;
    private LinearLayout prevNextRow;
    private ImageButton btnPrev;
    private ImageButton btnNext;

    // Active pin & delay editor
    private int activeIndex = -1;
    private View delayEditorView;
    private WindowManager.LayoutParams delayEditorParams;
    private EditText etDelay;
    private boolean delayEditorAdded = false;
    private boolean suppressDelayUpdate = false;

    private Handler mainHandler;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        inflateFloatingMenu();
        inflateDelayEditor();
        isOverlayVisible = true;
        notifyTile();
    }

    // -------------------------------------------------------------------------
    // Floating menu
    // -------------------------------------------------------------------------

    private void inflateFloatingMenu() {
        LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_AutoTapper));
        floatingMenu = inflater.inflate(R.layout.floating_control_menu, null);

        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.CENTER_VERTICAL | Gravity.END;

        btnAdd = floatingMenu.findViewById(R.id.btn_add);
        btnPlay = floatingMenu.findViewById(R.id.btn_play);
        btnSettings = floatingMenu.findViewById(R.id.btn_settings);
        prevNextRow = floatingMenu.findViewById(R.id.prev_next_row);
        btnPrev = floatingMenu.findViewById(R.id.btn_prev);
        btnNext = floatingMenu.findViewById(R.id.btn_next);

        btnAdd.setOnClickListener(v -> addNewTargetPin());
        btnPlay.setOnClickListener(v -> {
            if (isRunning) stopMacro(); else startMacro();
        });
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnPrev.setOnClickListener(v -> {
            if (tapTargets.isEmpty()) return;
            setActivePin((activeIndex - 1 + tapTargets.size()) % tapTargets.size());
        });
        btnNext.setOnClickListener(v -> {
            if (tapTargets.isEmpty()) return;
            setActivePin((activeIndex + 1) % tapTargets.size());
        });

        windowManager.addView(floatingMenu, menuParams);
    }

    // -------------------------------------------------------------------------
    // Delay editor overlay
    // -------------------------------------------------------------------------

    private void inflateDelayEditor() {
        LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_AutoTapper));
        delayEditorView = inflater.inflate(R.layout.delay_editor, null);

        delayEditorParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Focusable so keyboard appears; NOT_TOUCH_MODAL so touches outside pass through
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        delayEditorParams.gravity = Gravity.TOP | Gravity.START;
        delayEditorParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;

        etDelay = delayEditorView.findViewById(R.id.et_delay);
        etDelay.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (suppressDelayUpdate || activeIndex < 0 || activeIndex >= tapTargets.size()) return;
                try {
                    long val = Long.parseLong(s.toString());
                    if (val >= 50) tapTargets.get(activeIndex).delayMs = val;
                } catch (NumberFormatException ignored) {}
            }
        });

        ImageButton btnDelete = delayEditorView.findViewById(R.id.btn_delete_pin);
        btnDelete.setOnClickListener(v -> {
            if (activeIndex >= 0 && activeIndex < tapTargets.size()) {
                removePin(tapTargets.get(activeIndex));
            }
        });
    }

    private void showDelayEditor() {
        if (!delayEditorAdded) {
            windowManager.addView(delayEditorView, delayEditorParams);
            delayEditorAdded = true;
        } else {
            windowManager.updateViewLayout(delayEditorView, delayEditorParams);
        }
    }

    private void hideDelayEditor() {
        if (delayEditorAdded) {
            windowManager.removeView(delayEditorView);
            delayEditorAdded = false;
        }
    }

    private void updateDelayEditorPosition() {
        if (activeIndex < 0 || activeIndex >= tapTargets.size()) {
            hideDelayEditor();
            return;
        }
        WindowManager.LayoutParams pinParams = pinParamsList.get(activeIndex);
        delayEditorParams.x = pinParams.x;
        delayEditorParams.y = pinParams.y + dpToPx(60);
        showDelayEditor();
    }

    private void refreshDelayEditorValue() {
        if (activeIndex < 0 || activeIndex >= tapTargets.size()) return;
        suppressDelayUpdate = true;
        etDelay.setText(String.valueOf(tapTargets.get(activeIndex).delayMs));
        suppressDelayUpdate = false;
    }

    // -------------------------------------------------------------------------
    // Active pin management
    // -------------------------------------------------------------------------

    private void setActivePin(int index) {
        // Dim previously active pin
        if (activeIndex >= 0 && activeIndex < pinViews.size()) {
            pinViews.get(activeIndex).setAlpha(0.45f);
        }
        activeIndex = index;
        if (activeIndex >= 0 && activeIndex < pinViews.size()) {
            pinViews.get(activeIndex).setAlpha(1.0f);
            refreshDelayEditorValue();
            updateDelayEditorPosition();
        } else {
            hideDelayEditor();
        }
        updatePrevNextVisibility();
    }

    private void updatePrevNextVisibility() {
        prevNextRow.setVisibility(tapTargets.size() > 0 ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------------------------
    // Pins
    // -------------------------------------------------------------------------

    private void addNewTargetPin() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View pinView = inflater.inflate(R.layout.target_pin, null);

        int pinIndex = tapTargets.size() + 1;
        TextView pinLabel = pinView.findViewById(R.id.pin_label);
        pinLabel.setText(String.valueOf(pinIndex));

        WindowManager.LayoutParams pinParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        pinParams.gravity = Gravity.TOP | Gravity.START;
        pinParams.x = 300;
        pinParams.y = 300 + (pinIndex - 1) * 120;

        TapTarget target = new TapTarget(pinParams.x + dpToPx(28), pinParams.y + dpToPx(28), 500, pinIndex);
        tapTargets.add(target);
        pinViews.add(pinView);
        pinParamsList.add(pinParams);

        // New pin starts dimmed; setActivePin will restore it
        pinView.setAlpha(0.45f);
        attachPinTouchListener(pinView, pinParams, target);
        windowManager.addView(pinView, pinParams);

        setActivePin(tapTargets.size() - 1);
    }

    private void attachPinTouchListener(View pinView, WindowManager.LayoutParams pinParams, TapTarget target) {
        final float[] initialTouchX = {0};
        final float[] initialTouchY = {0};
        final int[] initialParamsX = {0};
        final int[] initialParamsY = {0};
        final boolean[] moved = {false};

        pinView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchX[0] = event.getRawX();
                    initialTouchY[0] = event.getRawY();
                    initialParamsX[0] = pinParams.x;
                    initialParamsY[0] = pinParams.y;
                    moved[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX[0];
                    float dy = event.getRawY() - initialTouchY[0];
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved[0] = true;
                    }
                    if (moved[0]) {
                        pinParams.x = initialParamsX[0] + (int) dx;
                        pinParams.y = initialParamsY[0] + (int) dy;
                        windowManager.updateViewLayout(pinView, pinParams);
                        int[] coords = new int[2];
                        pinView.getLocationOnScreen(coords);
                        target.x = coords[0] + pinView.getWidth() / 2;
                        target.y = coords[1] + pinView.getHeight() / 2;
                        // Keep delay editor attached to pin while dragging
                        int idx = pinViews.indexOf(pinView);
                        if (idx == activeIndex) {
                            updateDelayEditorPosition();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!moved[0]) {
                        // Tap = make this pin active
                        int idx = pinViews.indexOf(pinView);
                        if (idx >= 0) setActivePin(idx);
                    }
                    return true;
            }
            return false;
        });
    }

    private void removePin(TapTarget targetToRemove) {
        int idx = tapTargets.indexOf(targetToRemove);
        if (idx < 0) return;

        windowManager.removeView(pinViews.get(idx));
        tapTargets.remove(idx);
        pinViews.remove(idx);
        pinParamsList.remove(idx);

        // Re-index remaining pins
        for (int i = 0; i < tapTargets.size(); i++) {
            tapTargets.get(i).index = i + 1;
            ((TextView) pinViews.get(i).findViewById(R.id.pin_label)).setText(String.valueOf(i + 1));
        }

        // Update active index
        if (tapTargets.isEmpty()) {
            activeIndex = -1;
            hideDelayEditor();
            updatePrevNextVisibility();
        } else {
            // Don't call setActivePin (which re-dims); just clamp and refresh
            activeIndex = Math.min(idx, tapTargets.size() - 1);
            pinViews.get(activeIndex).setAlpha(1.0f);
            refreshDelayEditorValue();
            updateDelayEditorPosition();
            updatePrevNextVisibility();
        }
    }

    // -------------------------------------------------------------------------
    // Macro run/stop
    // -------------------------------------------------------------------------

    private void startMacro() {
        if (tapTargets.isEmpty()) {
            Toast.makeText(this, "No pins set!", Toast.LENGTH_SHORT).show();
            return;
        }
        isRunning = true;
        hideDelayEditor();
        btnAdd.setVisibility(View.GONE);
        btnSettings.setVisibility(View.GONE);
        prevNextRow.setVisibility(View.GONE);
        btnPlay.setImageResource(R.drawable.ic_stop);
        for (int i = 0; i < pinViews.size(); i++) {
            pinViews.get(i).setAlpha(1.0f);
            pinViews.get(i).setOnTouchListener(null);
            // FLAG_NOT_TOUCHABLE lets injected gestures pass through the pin window to the app beneath
            WindowManager.LayoutParams pp = pinParamsList.get(i);
            pp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(pinViews.get(i), pp);
        }
        dispatchTap(0);
    }

    private void dispatchTap(int index) {
        if (!isRunning || tapTargets.isEmpty()) return;
        int i = index % tapTargets.size();
        TapTarget target = tapTargets.get(i);

        // Visual pulse on the active pin
        View pinView = pinViews.get(i);
        pinView.animate().scaleX(1.5f).scaleY(1.5f).setDuration(60)
                .withEndAction(() -> pinView.animate().scaleX(1f).scaleY(1f).setDuration(60).start())
                .start();

        Path path = new Path();
        path.moveTo(target.x, target.y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();

        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                scheduleNextTap(i + 1, target.delayMs);
            }
            @Override
            public void onCancelled(GestureDescription g) {
                scheduleNextTap(i + 1, target.delayMs);
            }
        }, mainHandler);
    }

    private void scheduleNextTap(int nextIndex, long delayMs) {
        if (!isRunning) return;
        nextTapRunnable = () -> dispatchTap(nextIndex);
        mainHandler.postDelayed(nextTapRunnable, delayMs);
    }

    private void stopMacro() {
        isRunning = false;
        if (nextTapRunnable != null) {
            mainHandler.removeCallbacks(nextTapRunnable);
            nextTapRunnable = null;
        }
        btnAdd.setVisibility(View.VISIBLE);
        btnSettings.setVisibility(View.VISIBLE);
        btnPlay.setImageResource(R.drawable.ic_play);
        for (int i = 0; i < tapTargets.size(); i++) {
            pinViews.get(i).setAlpha(i == activeIndex ? 1.0f : 0.45f);
            pinViews.get(i).setScaleX(1f);
            pinViews.get(i).setScaleY(1f);
            // Restore touch interactivity
            WindowManager.LayoutParams pp = pinParamsList.get(i);
            pp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(pinViews.get(i), pp);
            attachPinTouchListener(pinViews.get(i), pinParamsList.get(i), tapTargets.get(i));
        }
        updatePrevNextVisibility();
        if (activeIndex >= 0) updateDelayEditorPosition();
    }

    // -------------------------------------------------------------------------
    // Profiles
    // -------------------------------------------------------------------------

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle("Profiles");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText profileNameInput = dialogView.findViewById(R.id.et_profile_name);

        builder.setView(dialogView);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = profileNameInput.getText().toString().trim();
            if (!name.isEmpty()) {
                saveProfile(name);
                Toast.makeText(this, "Saved: " + name, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Load", (dialog, which) -> {
            String name = profileNameInput.getText().toString().trim();
            if (!name.isEmpty()) loadProfile(name);
        });
        builder.setNeutralButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    private void saveProfile(String name) {
        try {
            JSONArray array = new JSONArray();
            for (TapTarget t : tapTargets) {
                JSONObject obj = new JSONObject();
                obj.put("x", t.x);
                obj.put("y", t.y);
                obj.put("delayMs", t.delayMs);
                obj.put("index", t.index);
                array.put(obj);
            }
            getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                    .edit().putString("profile_" + name, array.toString()).apply();
        } catch (JSONException e) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadProfile(String name) {
        String json = getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                .getString("profile_" + name, null);
        if (json == null) {
            Toast.makeText(this, "Profile not found: " + name, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            hideDelayEditor();
            activeIndex = -1;
            for (View pinView : pinViews) windowManager.removeView(pinView);
            tapTargets.clear();
            pinViews.clear();
            pinParamsList.clear();

            JSONArray array = new JSONArray(json);
            int half = dpToPx(28);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                int x = obj.getInt("x");
                int y = obj.getInt("y");
                long delayMs = obj.getLong("delayMs");
                int index = obj.getInt("index");

                View pinView = LayoutInflater.from(this).inflate(R.layout.target_pin, null);
                ((TextView) pinView.findViewById(R.id.pin_label)).setText(String.valueOf(index));
                pinView.setAlpha(0.45f);

                WindowManager.LayoutParams pinParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );
                pinParams.gravity = Gravity.TOP | Gravity.START;
                pinParams.x = x - half;
                pinParams.y = y - half;

                TapTarget target = new TapTarget(x, y, delayMs, index);
                tapTargets.add(target);
                pinViews.add(pinView);
                pinParamsList.add(pinParams);

                attachPinTouchListener(pinView, pinParams, target);
                windowManager.addView(pinView, pinParams);
            }
            Toast.makeText(this, "Loaded: " + name, Toast.LENGTH_SHORT).show();
            if (!tapTargets.isEmpty()) setActivePin(0);
            else updatePrevNextVisibility();
        } catch (JSONException e) {
            Toast.makeText(this, "Load failed", Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------------------------
    // Overlay toggle (Quick Settings tile)
    // -------------------------------------------------------------------------

    public void toggleOverlay() {
        if (isOverlayVisible) hideOverlay(); else showOverlay();
        notifyTile();
    }

    private void showOverlay() {
        if (!isOverlayVisible && floatingMenu != null) {
            windowManager.addView(floatingMenu, menuParams);
            for (int i = 0; i < pinViews.size(); i++) {
                windowManager.addView(pinViews.get(i), pinParamsList.get(i));
            }
            if (activeIndex >= 0) updateDelayEditorPosition();
            isOverlayVisible = true;
        }
    }

    private void hideOverlay() {
        if (isOverlayVisible) {
            if (isRunning) stopMacro();
            hideDelayEditor();
            for (View pin : pinViews) windowManager.removeView(pin);
            if (floatingMenu != null) windowManager.removeView(floatingMenu);
            isOverlayVisible = false;
        }
    }

    private void notifyTile() {
        TileService.requestListeningState(this,
                new ComponentName(this, AutoTapperTileService.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        hideDelayEditor();
        if (isOverlayVisible) {
            for (View pin : pinViews) windowManager.removeView(pin);
            if (floatingMenu != null) windowManager.removeView(floatingMenu);
        }
        notifyTile();
    }
}
