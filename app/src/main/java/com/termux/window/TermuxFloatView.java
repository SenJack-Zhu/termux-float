package com.termux.window;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.termux.window.settings.properties.TermuxFloatAppSharedProperties;

public class TermuxFloatView extends LinearLayout {

    public static final float ALPHA_FOCUS = 0.9f;
    public static final float ALPHA_NOT_FOCUS = 0.7f;
    public static final float ALPHA_MOVING = 0.5f;

    private int DISPLAY_WIDTH, DISPLAY_HEIGHT;

    final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
    WindowManager mWindowManager;

    private TerminalView mTerminalView;
    private ExtraKeysView mExtraKeysView;
    ViewGroup mWindowControls;
    FloatingBubbleManager mFloatingBubbleManager;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxFloatView}.
     */
    TermuxFloatViewClient mTermuxFloatViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxFloatService}.
     */
    TermuxFloatSessionClient mTermuxFloatSessionClient;

    /**
     * Termux Float app shared preferences manager.
     */
    private TermuxFloatAppSharedPreferences mPreferences;

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxFloatAppSharedProperties mProperties;

    private boolean withFocus = true;
    int initialX;
    int initialY;
    float initialTouchX;
    float initialTouchY;

    boolean isInLongPressState;

    final int[] location = new int[2];

    final int[] windowControlsLocation = new int[2];

    private static final String LOG_TAG = "TermuxFloatView";

    final ScaleGestureDetector mScaleDetector = new ScaleGestureDetector(getContext(), new OnScaleGestureListener() {
        private static final int MIN_SIZE = 50;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            int widthChange = (int) (detector.getCurrentSpanX() - detector.getPreviousSpanX());
            int heightChange = (int) (detector.getCurrentSpanY() - detector.getPreviousSpanY());
            layoutParams.width += widthChange;
            layoutParams.height += heightChange;
            layoutParams.width = Math.max(MIN_SIZE, layoutParams.width);
            layoutParams.height = Math.max(MIN_SIZE, layoutParams.height);
            mWindowManager.updateViewLayout(TermuxFloatView.this, layoutParams);
            if (mPreferences != null) {
                mPreferences.setWindowWidth(layoutParams.width);
                mPreferences.setWindowHeight(layoutParams.height);
            }
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            // Do nothing.
        }
    });

    public TermuxFloatView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAlpha(ALPHA_FOCUS);
    }

    private static int computeLayoutFlags(boolean withFocus) {
        // Always include FLAG_NOT_TOUCH_MODAL so touches outside the window pass through
        // to the underlying app/system (e.g. swipe-up for recent apps, notifications).
        if (withFocus) {
            return WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            return WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
    }

    public boolean initFloatView(TermuxFloatService service) {
        android.util.Log.e(LOG_TAG, "initFloatView START");

        // Load termux shared properties
        mProperties = new TermuxFloatAppSharedProperties(getContext());

        // Load termux float shared preferences
        // This will also fail if TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxFloatAppSharedPreferences.build(getContext(), true);
        if (mPreferences == null) {
            android.util.Log.e(LOG_TAG, "initFloatView: mPreferences is NULL, aborting");
            return false;
        }
        android.util.Log.e(LOG_TAG, "initFloatView: mPreferences OK, fontSize=" + mPreferences.getFontSize());

        mTermuxFloatSessionClient = new TermuxFloatSessionClient(service, this);

        mTerminalView = findViewById(R.id.terminal_view);
        mTermuxFloatViewClient = new TermuxFloatViewClient(this, mTermuxFloatSessionClient);
        mTerminalView.setTerminalViewClient(mTermuxFloatViewClient);
        mTermuxFloatViewClient.initFloatView();

        // Set up the extra keys bar (Ctrl, Alt, Esc, arrows, etc.)
        mExtraKeysView = findViewById(R.id.extra_keys_view);
        android.util.Log.e(LOG_TAG, "initFloatView: mExtraKeysView=" + mExtraKeysView);
        if (mExtraKeysView != null) {
            mExtraKeysView.setExtraKeysViewClient(new FloatExtraKeysClient(mTerminalView));
            // Delay reload until after layout so GridLayout has a measured width
            mExtraKeysView.post(this::setupExtraKeys);
        }

        mFloatingBubbleManager = new FloatingBubbleManager(this);
        initWindowControls();

        android.util.Log.e(LOG_TAG, "initFloatView DONE");
        return true;
    }

    private void initWindowControls() {
        mWindowControls = findViewById(R.id.window_controls);
        mWindowControls.setOnClickListener(v -> changeFocus(true));

        Button minimizeButton = findViewById(R.id.minimize_button);
        minimizeButton.setOnClickListener(v -> mFloatingBubbleManager.toggleBubble());

        Button exitButton = findViewById(R.id.exit_button);
        exitButton.setOnClickListener(v -> exit());
    }

    /**
     * Configure the extra keys bar with a default set of useful keys.
     * Row 1: ESC, TAB, CTRL, ALT, FN, HOME, END
     * Row 2: LEFT, DOWN, UP, RIGHT, COPY, PASTE, /, -
     *
     * COPY/PASTE are handled specially in {@link FloatExtraKeysClient} because the
     * system floating action-mode toolbar (copy menu) is unreliable inside a
     * TYPE_APPLICATION_OVERLAY window.
     */
    private void setupExtraKeys() {
        try {
            String extraKeysConfig = "[[\"ESC\",\"TAB\",\"CTRL\",\"ALT\",\"FN\",\"HOME\",\"END\"]," +
                    "[\"LEFT\",\"DOWN\",\"UP\",\"RIGHT\",\"COPY\",\"PASTE\",\"/\",\"-\"]]";
            ExtraKeysInfo extraKeysInfo = new ExtraKeysInfo(extraKeysConfig,
                    "default",
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            android.util.Log.e(LOG_TAG, "setupExtraKeys: fontSize=" + mPreferences.getFontSize() + ", matrix rows=" + extraKeysInfo.getMatrix().length);
            mExtraKeysView.reload(extraKeysInfo, mPreferences.getFontSize());
            android.util.Log.e(LOG_TAG, "setupExtraKeys: reload done, childCount=" + mExtraKeysView.getChildCount());
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "setupExtraKeys FAILED: " + e.getMessage(), e);
        }
    }

    /**
     * Custom extra-keys client that delegates normal keys to {@link TerminalExtraKeys}
     * but intercepts COPY and PASTE to handle them directly, since the system's
     * text-selection action-mode toolbar may not appear inside an overlay window.
     */
    private class FloatExtraKeysClient implements ExtraKeysView.IExtraKeysView {
        private final TerminalExtraKeys mDelegate;

        FloatExtraKeysClient(TerminalView terminalView) {
            mDelegate = new TerminalExtraKeys(terminalView);
        }

        @Override
        public void onExtraKeyButtonClick(View view, ExtraKeyButton button, MaterialButton materialButton) {
            if (button == null) return;
            String key = button.getKey();
            if ("COPY".equalsIgnoreCase(key)) {
                copySelectedText();
                return;
            }
            if ("PASTE".equalsIgnoreCase(key)) {
                pasteFromClipboard();
                return;
            }
            mDelegate.onExtraKeyButtonClick(view, button, materialButton);
        }

        @Override
        public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton button, MaterialButton materialButton) {
            return mDelegate.performExtraKeyButtonHapticFeedback(view, button, materialButton);
        }
    }

    /** Copy the currently selected terminal text to the clipboard. */
    private void copySelectedText() {
        try {
            String selected = mTerminalView.getSelectedText();
            if (selected == null || selected.isEmpty()) {
                Logger.showToast(getContext(), getContext().getString(R.string.no_text_selected), false);
                return;
            }
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("termux-float", selected));
                Logger.showToast(getContext(), getContext().getString(R.string.text_copied), false);
            }
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "copySelectedText failed: " + e.getMessage());
        }
    }

    /** Paste clipboard content into the terminal session. */
    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(getContext());
            if (text == null) return;
            TerminalSession session = mTerminalView.getCurrentSession();
            if (session != null) {
                session.write(text.toString());
            }
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "pasteFromClipboard failed: " + e.getMessage());
        }
    }

    /** Reload extra keys after font size change so they scale together. */
    public void reloadExtraKeys() {
        if (mExtraKeysView != null && mPreferences != null) {
            setupExtraKeys();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateDisplaySize();

        if (mTermuxFloatSessionClient != null)
            mTermuxFloatSessionClient.onAttachedToWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Refresh display dimensions when screen rotates or font scale changes
        updateDisplaySize();
    }

    /** Update DISPLAY_WIDTH and DISPLAY_HEIGHT using a non-deprecated API. */
    private void updateDisplaySize() {
        if (getDisplay() != null) {
            Point displaySize = new Point();
            getDisplay().getRealSize(displaySize);
            DISPLAY_WIDTH = displaySize.x;
            DISPLAY_HEIGHT = displaySize.y;
        } else {
            // Fallback when getDisplay() is null (e.g. before attach)
            android.util.DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            DISPLAY_WIDTH = dm.widthPixels;
            DISPLAY_HEIGHT = dm.heightPixels;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mTermuxFloatSessionClient != null)
            mTermuxFloatSessionClient.onDetachedFromWindow();
    }

    @SuppressLint("RtlHardcoded")
    public void launchFloatingWindow() {
        int widthAndHeight = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        layoutParams.flags = computeLayoutFlags(true);
        layoutParams.width = widthAndHeight;
        layoutParams.height = widthAndHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.format = PixelFormat.RGBA_8888;

        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;

        if (mPreferences != null) {
            layoutParams.x = mPreferences.getWindowX();
            layoutParams.y = mPreferences.getWindowY();
            int w = mPreferences.getWindowWidth();
            int h = mPreferences.getWindowHeight();
            // If using the library default (500x500), scale to a sensible fraction of the screen
            if (w <= 500 && h <= 500) {
                android.util.DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
                w = (int) (dm.widthPixels * 0.85f);
                h = (int) (dm.heightPixels * 0.55f);
            }
            layoutParams.width = w;
            layoutParams.height = h;
        }

        mWindowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (getWindowToken() == null)
            mWindowManager.addView(this, layoutParams);
        showTouchKeyboard();
    }

    private boolean isResizing;

    /**
     * Intercept touch events to handle window dragging, resizing and focus changes.
     *
     * Rules:
     *  - When minimized (bubble): any tap restores the floating window.
     *  - Two or more fingers anywhere: start pinch-to-resize.
     *  - Touch on the top control bar (not on a button): drag to move the window.
     *  - Touch on the terminal area: pass through to TerminalView so long-press
     *    text selection and keyboard input keep working.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // If currently dragging or resizing, keep owning the gesture.
        if (isInLongPressState || isResizing) return true;

        // Minimized bubble: any tap restores the floating window.
        if (mFloatingBubbleManager != null && mFloatingBubbleManager.isMinimized()) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                mFloatingBubbleManager.displayAsFloatingWindow();
                changeFocus(true);
                showTouchKeyboard();
            }
            return true;
        }

        float touchX = event.getRawX();
        float touchY = event.getRawY();

        // Multi-finger pinch: intercept for window resize.
        if (event.getPointerCount() >= 2) {
            isResizing = true;
            setAlpha(ALPHA_MOVING);
            return true;
        }

        // Dragging from the control bar (blank area between the two buttons).
        if (didClickInsideWindowControls(touchX, touchY) && !didClickOnControlButton(touchX, touchY)) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                getLocationOnScreen(location);
                updateLongPressMode(true);
                initialX = location[0];
                initialY = location[1];
                initialTouchX = touchX;
                initialTouchY = touchY;
                return true;
            }
        }

        // Taps on the terminal area: let them reach TerminalView (text selection, typing).
        // We still manage focus on UP.
        if (event.getAction() == MotionEvent.ACTION_UP) {
            getLocationOnScreen(location);
            boolean clickedInside = (touchX >= location[0] && touchX <= location[0] + layoutParams.width)
                    && (touchY >= location[1] && touchY <= location[1] + layoutParams.height);
            if (clickedInside && !didClickInsideWindowControls(touchX, touchY)) {
                changeFocus(true);
                showTouchKeyboard();
            }
        }

        return false;
    }

    private boolean didClickOnControlButton(float touchX, float touchY) {
        int[] btnLoc = new int[2];
        View minimize = findViewById(R.id.minimize_button);
        View exit = findViewById(R.id.exit_button);
        for (View btn : new View[]{minimize, exit}) {
            if (btn == null || btn.getVisibility() != View.VISIBLE) continue;
            btn.getLocationOnScreen(btnLoc);
            if (touchX >= btnLoc[0] && touchX <= btnLoc[0] + btn.getWidth()
                    && touchY >= btnLoc[1] && touchY <= btnLoc[1] + btn.getHeight()) {
                return true;
            }
        }
        return false;
    }

    private boolean didClickInsideWindowControls(float touchX, float touchY) {
        if (mWindowControls == null || mWindowControls.getVisibility() == View.GONE) {
            return false;
        }
        mWindowControls.getLocationOnScreen(windowControlsLocation);
        int controlsX = windowControlsLocation[0];
        int controlsY = windowControlsLocation[1];

        return (touchX >= controlsX && touchX <= controlsX + mWindowControls.getWidth()) &&
                (touchY >= controlsY && touchY <= controlsY + mWindowControls.getHeight());
    }

    void showTouchKeyboard() {
        mTerminalView.post(() -> KeyboardUtils.showSoftKeyboard(getContext(), mTerminalView));

    }

    void hideTouchKeyboard() {
        mTerminalView.post(() -> KeyboardUtils.hideSoftKeyboard(getContext(), mTerminalView));
    }

    void updateLongPressMode(boolean newValue) {
        isInLongPressState = newValue;
        mFloatingBubbleManager.updateLongPressBackgroundResource(isInLongPressState);
        setAlpha(newValue ? ALPHA_MOVING : (withFocus ? ALPHA_FOCUS : ALPHA_NOT_FOCUS));
        if (newValue && !mFloatingBubbleManager.isMinimized())
            Logger.showToast(getContext(), getContext().getString(R.string.after_long_press), false);
    }

    /**
     * Motion events should only be dispatched here when {@link #onInterceptTouchEvent(MotionEvent)} returns true.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isResizing) {
            mScaleDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                isResizing = false;
                setAlpha(withFocus ? ALPHA_FOCUS : ALPHA_NOT_FOCUS);
            }
            return true;
        }

        if (isInLongPressState) {
            mScaleDetector.onTouchEvent(event);
            if (mScaleDetector.isInProgress()) return true;
            switch (event.getAction()) {
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = Math.min(DISPLAY_WIDTH - layoutParams.width, Math.max(0, initialX + (int) (event.getRawX() - initialTouchX)));
                    layoutParams.y = Math.min(DISPLAY_HEIGHT - layoutParams.height, Math.max(0, initialY + (int) (event.getRawY() - initialTouchY)));
                    mWindowManager.updateViewLayout(TermuxFloatView.this, layoutParams);
                    if (mPreferences != null) {
                        mPreferences.setWindowX(layoutParams.x);
                        mPreferences.setWindowY(layoutParams.y);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    updateLongPressMode(false);
                    break;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Visually indicate focus and show the soft input as needed.
     */
    void changeFocus(boolean newFocus) {
        if (newFocus && mFloatingBubbleManager.isMinimized()) {
            mFloatingBubbleManager.displayAsFloatingWindow();
        }
        if (newFocus == withFocus) {
            if (newFocus) showTouchKeyboard();
            return;
        }
        withFocus = newFocus;
        layoutParams.flags = computeLayoutFlags(withFocus);
        if (getWindowToken() != null)
            mWindowManager.updateViewLayout(this, layoutParams);
        setAlpha(newFocus ? ALPHA_FOCUS : ALPHA_NOT_FOCUS);
    }

    public void closeFloatingWindow() {
        if (getWindowToken() != null)
            mWindowManager.removeView(this);

        mFloatingBubbleManager.cleanup();
        mFloatingBubbleManager = null;
    }

    private void exit() {
        Intent exitIntent = new Intent(getContext(), TermuxFloatService.class).setAction(TermuxConstants.TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE.ACTION_STOP_SERVICE);
        getContext().startService(exitIntent);
    }



    public boolean isVisible() {
        return isAttachedToWindow() && isShown();
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxFloatViewClient getTermuxFloatViewClient() {
        return mTermuxFloatViewClient;
    }

    public TermuxFloatSessionClient getTermuxFloatSessionClient() {
        return mTermuxFloatSessionClient;
    }

    public TermuxFloatAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxFloatAppSharedProperties getProperties() {
        return mProperties;
    }


    public void reloadViewStyling() {
        // Leaving here for future support for termux-reload-settings
        if (mTermuxFloatSessionClient != null)
            mTermuxFloatSessionClient.onReload();
    }
}
