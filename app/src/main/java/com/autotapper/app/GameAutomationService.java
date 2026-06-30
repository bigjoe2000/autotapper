package com.autotapper.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
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
    private boolean isNavigateMode = false;
    private ImageButton btnNavigate;

    private enum MenuEdge { LEFT, RIGHT, TOP, BOTTOM }
    private MenuEdge menuEdge = MenuEdge.RIGHT;
    private boolean menuIsHorizontal = false;

    private Runnable nextTapRunnable;

    private ImageButton btnAdd;
    private ImageButton btnPlay;
    private ImageButton btnSettings;
    private LinearLayout prevNextRow;
    private ImageButton btnPrev;
    private ImageButton btnNext;

    private String currentProfileName = null;
    private String currentForegroundPackage = null;
    private String currentForegroundClass = null;
    private boolean continueOnScreenChange = false;

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

        btnNavigate = floatingMenu.findViewById(R.id.btn_navigate);
        btnNavigate.setOnClickListener(v -> setNavigateMode(!isNavigateMode));

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
        attachMenuDragListener();
    }

    private void attachMenuDragListener() {
        View handle = floatingMenu.findViewById(R.id.menu_drag_handle);
        final float[] startTouchX = {0};
        final float[] startTouchY = {0};
        final int[] startParamsX = {0};
        final int[] startParamsY = {0};

        handle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    // Convert gravity-relative position to absolute TOP|START coords once
                    int[] loc = new int[2];
                    floatingMenu.getLocationOnScreen(loc);
                    menuParams.gravity = Gravity.TOP | Gravity.START;
                    menuParams.x = loc[0];
                    menuParams.y = loc[1];
                    windowManager.updateViewLayout(floatingMenu, menuParams);
                    startTouchX[0] = event.getRawX();
                    startTouchY[0] = event.getRawY();
                    startParamsX[0] = menuParams.x;
                    startParamsY[0] = menuParams.y;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    menuParams.x = startParamsX[0] + (int) (event.getRawX() - startTouchX[0]);
                    menuParams.y = startParamsY[0] + (int) (event.getRawY() - startTouchY[0]);
                    windowManager.updateViewLayout(floatingMenu, menuParams);
                    // Flip orientation live as the menu crosses edge thresholds
                    int sw2 = getResources().getDisplayMetrics().widthPixels;
                    int sh2 = getResources().getDisplayMetrics().heightPixels;
                    int cx2 = menuParams.x + floatingMenu.getWidth() / 2;
                    int cy2 = menuParams.y + floatingMenu.getHeight() / 2;
                    float dL2 = cx2, dR2 = sw2 - cx2, dT2 = cy2, dB2 = sh2 - cy2;
                    float min2 = Math.min(Math.min(dL2, dR2), Math.min(dT2, dB2));
                    boolean wantsHoriz = (min2 == dT2 || min2 == dB2);
                    if (wantsHoriz != menuIsHorizontal) applyMenuOrientation(wantsHoriz);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int sw = getResources().getDisplayMetrics().widthPixels;
                    int sh = getResources().getDisplayMetrics().heightPixels;
                    int mw = floatingMenu.getWidth();
                    int mh = floatingMenu.getHeight();
                    int cx = menuParams.x + mw / 2;
                    int cy = menuParams.y + mh / 2;
                    float dL = cx, dR = sw - cx, dT = cy, dB = sh - cy;
                    float min = Math.min(Math.min(dL, dR), Math.min(dT, dB));
                    if (min == dR) {
                        menuEdge = MenuEdge.RIGHT;
                        menuParams.x = sw - mw;
                        menuParams.y = Math.max(0, Math.min(menuParams.y, sh - mh));
                    } else if (min == dL) {
                        menuEdge = MenuEdge.LEFT;
                        menuParams.x = 0;
                        menuParams.y = Math.max(0, Math.min(menuParams.y, sh - mh));
                    } else if (min == dT) {
                        menuEdge = MenuEdge.TOP;
                        menuParams.x = Math.max(0, Math.min(menuParams.x, sw - mw));
                        menuParams.y = 0;
                    } else {
                        menuEdge = MenuEdge.BOTTOM;
                        menuParams.x = Math.max(0, Math.min(menuParams.x, sw - mw));
                        menuParams.y = sh - mh;
                    }
                    applyMenuOrientation(menuEdge == MenuEdge.TOP || menuEdge == MenuEdge.BOTTOM);
                    windowManager.updateViewLayout(floatingMenu, menuParams);
                    updateDelayEditorPosition();
                    return true;
                }
            }
            return false;
        });
    }

    private void applyMenuOrientation(boolean horiz) {
        if (horiz == menuIsHorizontal) return;
        menuIsHorizontal = horiz;
        ((LinearLayout) floatingMenu).setOrientation(horiz ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        View dragHandle = floatingMenu.findViewById(R.id.menu_drag_handle);
        LinearLayout.LayoutParams dhLp = (LinearLayout.LayoutParams) dragHandle.getLayoutParams();
        dhLp.width  = horiz ? dpToPx(20) : dpToPx(48);
        dhLp.height = horiz ? dpToPx(48) : dpToPx(20);
        dragHandle.setLayoutParams(dhLp);

        LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) prevNextRow.getLayoutParams();
        rowLp.width  = horiz ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT;
        rowLp.height = horiz ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT;
        prevNextRow.setLayoutParams(rowLp);
        for (int i = 0; i < prevNextRow.getChildCount(); i++) {
            View child = prevNextRow.getChildAt(i);
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) child.getLayoutParams();
            clp.width  = horiz ? dpToPx(48) : 0;
            clp.height = dpToPx(48);
            clp.weight = horiz ? 0 : 1;
            child.setLayoutParams(clp);
        }
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

        attachDelayEditorDragListener();

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

    private void attachDelayEditorDragListener() {
        View handle = delayEditorView.findViewById(R.id.drag_handle);
        final float[] startTouchX = {0};
        final float[] startTouchY = {0};
        final int[] startParamsX = {0};
        final int[] startParamsY = {0};
        handle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startTouchX[0] = event.getRawX();
                    startTouchY[0] = event.getRawY();
                    startParamsX[0] = delayEditorParams.x;
                    startParamsY[0] = delayEditorParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    delayEditorParams.x = startParamsX[0] + (int) (event.getRawX() - startTouchX[0]);
                    delayEditorParams.y = startParamsY[0] + (int) (event.getRawY() - startTouchY[0]);
                    if (delayEditorAdded) windowManager.updateViewLayout(delayEditorView, delayEditorParams);
                    return true;
            }
            return false;
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

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int editorW = dpToPx(210);
        int editorH = dpToPx(48);
        int menuSide = dpToPx(72);   // menu footprint on left/right edge
        int menuBar  = dpToPx(260);  // menu footprint on top/bottom edge

        int x = pinParams.x;
        // Prefer below pin; flip above if that would clip the bottom
        int y = pinParams.y + dpToPx(60);
        if (y + editorH > sh) y = pinParams.y - dpToPx(60) - editorH;

        switch (menuEdge) {
            case RIGHT:
                x = Math.max(0, Math.min(x, sw - menuSide - editorW));
                break;
            case LEFT:
                x = Math.max(menuSide, Math.min(x, sw - editorW));
                break;
            case TOP:
                x = Math.max(0, Math.min(x, sw - editorW));
                y = Math.max(menuBar, y);
                break;
            case BOTTOM:
                x = Math.max(0, Math.min(x, sw - editorW));
                y = Math.min(y, sh - menuBar - editorH);
                break;
        }
        y = Math.max(0, Math.min(y, sh - editorH));

        delayEditorParams.x = x;
        delayEditorParams.y = y;
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

    private void setNavigateMode(boolean on) {
        isNavigateMode = on;
        btnNavigate.setAlpha(on ? 1.0f : 0.45f);
        for (int i = 0; i < pinViews.size(); i++) {
            if (on) pinParamsList.get(i).flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else    pinParamsList.get(i).flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            pinViews.get(i).setAlpha(on ? 0.3f : (i == activeIndex ? 1.0f : 0.45f));
            windowManager.updateViewLayout(pinViews.get(i), pinParamsList.get(i));
        }
        if (on) {
            hideDelayEditor();
        } else {
            updateDelayEditorPosition();
        }
    }

    private void addNewTargetPin() {
        if (isNavigateMode) setNavigateMode(false);
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
        if (isNavigateMode) setNavigateMode(false);
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
        String profileDisplay = currentProfileName != null ? currentProfileName : "(none)";
        String appDisplay = currentForegroundPackage != null
                ? getAppDisplayName(currentForegroundPackage) : "(none)";

        List<String> options = new ArrayList<>();
        options.add(currentProfileName != null ? "Save  —  " + currentProfileName : "Save");
        options.add("New profile");
        options.add("Load profile");

        AlertDialog.Builder builder = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle("Profile:  " + profileDisplay + "\nApp:  " + appDisplay);
        builder.setItems(options.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) {
                if (currentProfileName != null) {
                    saveProfile(currentProfileName, getProfilePackage(currentProfileName));
                    Toast.makeText(this, "Saved: " + currentProfileName, Toast.LENGTH_SHORT).show();
                } else {
                    showNewProfileDialog();
                }
            } else if (which == 1) {
                showNewProfileDialog();
            } else if (which == 2) {
                showLoadProfileDialog();
            }
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    private void showNewProfileDialog() {
        // Build a small layout: name input + optional app-link checkbox
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        layout.setPadding(pad, dpToPx(8), pad, 0);

        EditText input = new EditText(this);
        input.setHint("Profile name");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        layout.addView(input);

        CheckBox cbLink = new CheckBox(this);
        if (currentForegroundPackage != null) {
            cbLink.setText("Link to  " + getAppDisplayName(currentForegroundPackage));
            cbLink.setChecked(true);
            layout.addView(cbLink);
        }

        CheckBox cbContinue = new CheckBox(this);
        cbContinue.setText("Allow tapping to continue on screen change");
        cbContinue.setChecked(false);
        layout.addView(cbContinue);

        AlertDialog.Builder builder = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle("New Profile");
        builder.setView(layout);
        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                String pkg = (cbLink.getParent() != null && cbLink.isChecked())
                        ? currentForegroundPackage : null;
                continueOnScreenChange = cbContinue.isChecked();
                saveProfile(name, pkg);
                Toast.makeText(this, "Created: " + name, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    private void showLoadProfileDialog() {
        // Build display list: profiles for current app starred at top, rest below
        List<String[]> all = getAllProfiles(); // {name, pkg}
        if (all.isEmpty()) {
            Toast.makeText(this, "No saved profiles", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> displayItems = new ArrayList<>();
        List<String> nameItems = new ArrayList<>();
        for (String[] p : all) {
            boolean forCurrentApp = currentForegroundPackage != null
                    && currentForegroundPackage.equals(p[1]);
            String label = (forCurrentApp ? "★  " : "    ") + p[0];
            if (p[1] != null && !forCurrentApp) label += "  (" + getAppDisplayName(p[1]) + ")";
            displayItems.add(label);
            nameItems.add(p[0]);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle("Profiles");
        builder.setItems(displayItems.toArray(new String[0]),
                (dialog, which) -> showProfileActionDialog(nameItems.get(which)));
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    private void showProfileActionDialog(String name) {
        String profilePkg = getProfilePackage(name);
        boolean linkedToCurrentApp = currentForegroundPackage != null
                && currentForegroundPackage.equals(profilePkg);
        boolean canLink = currentForegroundPackage != null;

        boolean profileContinues = getProfileContinueOnScreenChange(name);

        List<String> actions = new ArrayList<>();
        actions.add("Load");
        if (canLink) {
            actions.add(linkedToCurrentApp
                    ? "Unlink from  " + getAppDisplayName(currentForegroundPackage)
                    : "Link to  " + getAppDisplayName(currentForegroundPackage));
        }
        actions.add(profileContinues
                ? "Screen change:  continue tapping"
                : "Screen change:  stop tapping");
        actions.add("Delete");

        AlertDialog.Builder builder = new AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        builder.setTitle(name);
        builder.setItems(actions.toArray(new String[0]), (dialog, which) -> {
            String action = actions.get(which);
            if (action.equals("Load")) {
                loadProfile(name);
            } else if (action.startsWith("Link")) {
                relinkProfile(name, currentForegroundPackage);
            } else if (action.startsWith("Unlink")) {
                relinkProfile(name, null);
            } else if (action.startsWith("Screen change")) {
                setProfileContinueOnScreenChange(name, !profileContinues);
            } else if (action.equals("Delete")) {
                deleteProfile(name);
            }
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    private void deleteProfile(String name) {
        getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                .edit().remove("profile_" + name).apply();
        if (name.equals(currentProfileName)) currentProfileName = null;
        Toast.makeText(this, "Deleted: " + name, Toast.LENGTH_SHORT).show();
    }

    // Returns {name, pkg|null} sorted by name, app-linked profiles first
    private List<String[]> getAllProfiles() {
        SharedPreferences prefs = getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE);
        List<String[]> result = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("profile_")) continue;
            String n = key.substring("profile_".length());
            result.add(new String[]{n, getProfilePackage(n)});
        }
        result.sort((a, b) -> {
            // Current app's profiles sort first
            boolean aMatch = currentForegroundPackage != null && currentForegroundPackage.equals(a[1]);
            boolean bMatch = currentForegroundPackage != null && currentForegroundPackage.equals(b[1]);
            if (aMatch != bMatch) return aMatch ? -1 : 1;
            return a[0].compareTo(b[0]);
        });
        return result;
    }

    private String getProfilePackage(String name) {
        String json = getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                .getString("profile_" + name, null);
        if (json == null || json.startsWith("[")) return null; // old array format has no pkg
        try { return new JSONObject(json).optString("pkg", null); }
        catch (JSONException e) { return null; }
    }

    private void saveProfile(String name, String pkg) {
        try {
            JSONArray targets = new JSONArray();
            for (TapTarget t : tapTargets) {
                JSONObject obj = new JSONObject();
                obj.put("x", t.x);
                obj.put("y", t.y);
                obj.put("delayMs", t.delayMs);
                obj.put("index", t.index);
                targets.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("targets", targets);
            if (pkg != null) root.put("pkg", pkg);
            root.put("continueOnScreenChange", continueOnScreenChange);
            getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                    .edit().putString("profile_" + name, root.toString()).apply();
            currentProfileName = name;
        } catch (JSONException e) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void relinkProfile(String name, String newPkg) {
        String json = getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                .getString("profile_" + name, null);
        if (json == null) return;
        try {
            JSONObject root = json.startsWith("[")
                    ? new JSONObject().put("targets", new JSONArray(json))
                    : new JSONObject(json);
            if (newPkg != null) root.put("pkg", newPkg); else root.remove("pkg");
            getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                    .edit().putString("profile_" + name, root.toString()).apply();
            String label = newPkg != null
                    ? "Linked to " + getAppDisplayName(newPkg) : "Unlinked";
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean getProfileContinueOnScreenChange(String name) {
        String json = getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                .getString("profile_" + name, null);
        if (json == null || json.startsWith("[")) return false;
        try { return new JSONObject(json).optBoolean("continueOnScreenChange", false); }
        catch (JSONException e) { return false; }
    }

    private void setProfileContinueOnScreenChange(String name, boolean value) {
        String json = getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                .getString("profile_" + name, null);
        if (json == null) return;
        try {
            JSONObject root = json.startsWith("[")
                    ? new JSONObject().put("targets", new JSONArray(json))
                    : new JSONObject(json);
            root.put("continueOnScreenChange", value);
            getSharedPreferences("autotapper_profiles", Context.MODE_PRIVATE)
                    .edit().putString("profile_" + name, root.toString()).apply();
            if (name.equals(currentProfileName)) continueOnScreenChange = value;
            Toast.makeText(this, value ? "Will continue on screen change" : "Will stop on screen change",
                    Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show();
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
            // Support both old (plain array) and new ({pkg, targets}) formats
            JSONObject root = json.startsWith("[") ? null : new JSONObject(json);
            JSONArray array = root != null ? root.getJSONArray("targets") : new JSONArray(json);
            continueOnScreenChange = root != null && root.optBoolean("continueOnScreenChange", false);

            hideDelayEditor();
            activeIndex = -1;
            for (View pinView : pinViews) windowManager.removeView(pinView);
            tapTargets.clear();
            pinViews.clear();
            pinParamsList.clear();

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
            currentProfileName = name;
            Toast.makeText(this, "Loaded: " + name, Toast.LENGTH_SHORT).show();
            if (!tapTargets.isEmpty()) setActivePin(0);
            else updatePrevNextVisibility();
        } catch (JSONException e) {
            Toast.makeText(this, "Load failed", Toast.LENGTH_SHORT).show();
        }
    }

    private String getAppDisplayName(String packageName) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();
        if (packageName.equals(getPackageName())) return;

        String className = event.getClassName() != null ? event.getClassName().toString() : "";
        boolean changed = !packageName.equals(currentForegroundPackage)
                       || !className.equals(currentForegroundClass);
        if (!changed) return;

        currentForegroundPackage = packageName;
        currentForegroundClass = className;
        if (isRunning && !continueOnScreenChange) stopMacro();
    }
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Post so the view has been re-measured with the new screen dimensions before we reposition
        mainHandler.post(() -> {
            if (menuParams.gravity != (Gravity.TOP | Gravity.START)) return;
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            int mw = floatingMenu.getWidth();
            int mh = floatingMenu.getHeight();
            if (mw == 0 || mh == 0) return;
            switch (menuEdge) {
                case RIGHT:
                    menuParams.x = sw - mw;
                    menuParams.y = Math.max(0, Math.min(menuParams.y, sh - mh));
                    break;
                case LEFT:
                    menuParams.x = 0;
                    menuParams.y = Math.max(0, Math.min(menuParams.y, sh - mh));
                    break;
                case TOP:
                    menuParams.x = Math.max(0, Math.min(menuParams.x, sw - mw));
                    menuParams.y = 0;
                    break;
                case BOTTOM:
                    menuParams.x = Math.max(0, Math.min(menuParams.x, sw - mw));
                    menuParams.y = sh - mh;
                    break;
            }
            windowManager.updateViewLayout(floatingMenu, menuParams);
            updateDelayEditorPosition();
        });
    }

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
