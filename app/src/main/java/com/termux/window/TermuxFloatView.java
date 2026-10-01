package com.termux.window;

import android.annotation.SuppressLint;
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

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
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
        if (withFocus) {
            return 0;
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
            mExtraKeysView.setExtraKeysViewClient(new TerminalExtraKeys(mTerminalView));
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
     * Layout:
     *   Row 1: ESC, TAB, CTRL, ALT, FN, HOME, END
     *   Row 2: LEFT, DOWN, UP, RIGHT, PGUP, PGDN, /, -
     */
    private void setupExtraKeys() {
        try {
            String extraKeysConfig = "[[\"ESC\",\"TAB\",\"CTRL\",\"ALT\",\"FN\",\"HOME\",\"END\"]," +
                    "[\"LEFT\",\"DOWN\",\"UP\",\"RIGHT\",\"PGUP\",\"PGDN\",\"/\",\"-\"]]";
            ExtraKeysInfo extraKeysInfo = new ExtraKeysInfo(extraKeysConfig,
                    (com.termux.shared.termux.extrakeys.ExtraKeysConstants.ExtraKeyDisplayMap) null,
                    (com.termux.shared.termux.extrakeys.ExtraKeysConstants.ExtraKeyDisplayMap) null);
            android.util.Log.e(LOG_TAG, "setupExtraKeys: fontSize=" + mPreferences.getFontSize() + ", matrix rows=" + extraKeysInfo.getMatrix().length);
            mExtraKeysView.reload(extraKeysInfo, mPreferences.getFontSize());
            android.util.Log.e(LOG_TAG, "setupExtraKeys: reload done, childCount=" + mExtraKeysView.getChildCount());
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "setupExtraKeys FAILED: " + e.getMessage(), e);
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
        if (getDisplay() == null) return;
        Point displaySize = new Point();
        getDisplay().getRealSize(displaySize);
        DISPLAY_WIDTH = displaySize.x;
        DISPLAY_HEIGHT = displaySize.y;
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

    /**
     * Intercept touch events to obtain and loose focus on touch events.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isInLongPressState) return true;

        getLocationOnScreen(location);
        int x = location[0];
        int y = location[1];
        float touchX = event.getRawX();
        float touchY = event.getRawY();

        if (didClickInsideWindowControls(touchX, touchY)) {
            // Dragging from the control bar blank area moves the window
            if (event.getAction() == MotionEvent.ACTION_DOWN && didClickOnDragHandle(touchX, touchY)) {
                updateLongPressMode(true);
                initialX = x;
                initialY = y;
                initialTouchX = touchX;
                initialTouchY = touchY;
                return true;
            }
            // avoid unintended focus event if we are tapping on our window controls
            // so that keyboard doesn't possibly show briefly
            return false;
        }

        boolean clickedInside = (touchX >= x) && (touchX <= (x + layoutParams.width)) && (touchY >= y) && (touchY <= (y + layoutParams.height));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!clickedInside) changeFocus(false);
                break;
            case MotionEvent.ACTION_UP:
                if (clickedInside) {
                    changeFocus(true);
                    showTouchKeyboard();
                }
                break;
        }
        return false;
    }

    private boolean didClickOnDragHandle(float touchX, float touchY) {
        View dragHandle = findViewById(R.id.drag_handle);
        if (dragHandle == null || dragHandle.getVisibility() == View.GONE) return false;
        dragHandle.getLocationOnScreen(windowControlsLocation);
        int hx = windowControlsLocation[0];
        int hy = windowControlsLocation[1];
        return (touchX >= hx && touchX <= hx + dragHandle.getWidth()) &&
                (touchY >= hy && touchY <= hy + dragHandle.getHeight());
    }

    private boolean didClickInsideWindowControls(float touchX, float touchY) {
        if (mWindowControls.getVisibility() == View.GONE) {
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
     * Motion events should only be dispatched here when {@link #onInterceptTouchEvent(MotionEvent)} returns true.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
