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
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.termux.window.settings.properties.TermuxFloatAppSharedProperties;

public class TermuxFloatView extends LinearLayout {

    public static final float ALPHA_FOCUS = 0.9f;
    public static final float ALPHA_NOT_FOCUS = 0.7f;
    public static final float ALPHA_MOVING = 0.5f;

    /**
     * Extra keys layout: two rows of 14 keys, so the whole bar stays about as tall as the soft
     * keyboard's suggestion strip while covering the keys that are painful to type on a phone.
     *
     * Sticky keys (state toggled by ExtraKeysView itself and read through
     * {@link #readExtraKeysSpecialButton(SpecialButton)}): CTRL, ALT, SHIFT, FN.
     * Handled by {@link FloatExtraKeysClient}: CPY, PSTE, KBRD, ALL, and the 0xNN control
     * code points (0x03 = Ctrl-C, 0x04 = Ctrl-D, 0x0c = Ctrl-L, 0x1a = Ctrl-Z).
     *
     * Displays are kept to 2-4 characters because a 14 column bar on a 1080 px wide window gives
     * roughly 74 px per key.
     */
    private static final String EXTRA_KEYS_CONFIG =
            "[[" +
                    "{key: \"ESC\", display: \"ESC\"}," +
                    "{key: \"TAB\", display: \"TAB\"}," +
                    "{key: \"CTRL\", display: \"CTL\"}," +
                    "{key: \"ALT\", display: \"ALT\"}," +
                    "{key: \"SHIFT\", display: \"SHF\"}," +
                    "{key: \"FN\", display: \"FN\"}," +
                    "{key: \"LEFT\", display: \"<\"}," +
                    "{key: \"DOWN\", display: \"v\"}," +
                    "{key: \"UP\", display: \"^\"}," +
                    "{key: \"RIGHT\", display: \">\"}" +
                    "],[" +
                    "{key: \"PGUP\", display: \"PG^\"}," +
                    "{key: \"PGDN\", display: \"PGv\"}," +
                    "{key: \"HOME\", display: \"HOM\"}," +
                    "{key: \"END\", display: \"END\"}," +
                    "{key: \"/\", display: \"/\"}," +
                    "{key: \"-\", display: \"-\"}," +
                    "{key: \"|\", display: \"|\"}," +
                    "{key: \"~\", display: \"~\"}," +
                    "{key: \"KBRD\", display: \"KBD\"}," +
                    "{key: \"CPY\", display: \"CPY\"}," +
                    "{key: \"PSTE\", display: \"PST\"}," +
                    "{key: \"ALL\", display: \"ALL\"}," +
                    "{key: \"0x03\", display: \"^C\"}," +
                    "{key: \"0x04\", display: \"^D\"}," +
                    "{key: \"0x0c\", display: \"^L\"}," +
                    "{key: \"0x1a\", display: \"^Z\"}" +
                    "]]";

    /** Text size (in px, i.e. the terminal font size) used for the extra keys labels. */
    private static final int EXTRA_KEYS_LABEL_SIZE_PX = 24;

    /** Minimum window width/height in px when pinch-resizing. */
    private static final int MIN_WINDOW_SIZE = 180;
    /** Minimum window height in px to keep the keyboard-shifted window usable. */
    private static final int MIN_IME_WINDOW_HEIGHT = 220;
    /** Fraction of the display height above which a system window is assumed to be the IME. */
    private static final float IME_MIN_FRACTION = 0.15f;
    /**
     * Extra clearance (px) kept above the IME.
     *
     * <p>The value reported by {@code getWindowVisibleDisplayFrame()} describes the IME window, but
     * most third party keyboards draw an additional toolbar (clipboard/emoji/suggestion row) as a
     * separate window that still overlaps the floating window. Without this margin the bottom
     * hotkey row ends up underneath that toolbar and cannot be tapped.</p>
     */
    private static final int IME_CLEARANCE_PX = 110;

    private static final String LOG_TAG = "TermuxFloatView";

    private int DISPLAY_WIDTH, DISPLAY_HEIGHT;

    final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
    WindowManager mWindowManager;

    private TerminalView mTerminalView;
    private ExtraKeysView mExtraKeysView;
    ViewGroup mWindowControls;
    private View mSelectionBar;
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

    final ScaleGestureDetector mScaleDetector = new ScaleGestureDetector(getContext(), new OnScaleGestureListener() {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            int widthChange = (int) (detector.getCurrentSpanX() - detector.getPreviousSpanX());
            int heightChange = (int) (detector.getCurrentSpanY() - detector.getPreviousSpanY());
            layoutParams.width = Math.max(MIN_WINDOW_SIZE, layoutParams.width + widthChange);
            layoutParams.height = Math.max(getMinWindowHeight(), layoutParams.height + heightChange);
            mWindowManager.updateViewLayout(TermuxFloatView.this, layoutParams);
            if (mPreferences != null) {
                mPreferences.setWindowWidth(layoutParams.width);
                mPreferences.setWindowHeight(layoutParams.height);
            }
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            // Persist the final size once the gesture is over.
            if (mPreferences != null) {
                mPreferences.setWindowWidth(layoutParams.width);
                mPreferences.setWindowHeight(layoutParams.height);
            }
        }
    });

    /** Keyboard (IME) handling. */
    private ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener;
    private boolean mImeVisible;
    private int mImeShift;              // px the window was shifted up because of the IME
    private int mBaseWindowY;           // window y as set by the user (without the IME shift)
    private int mBaseWindowHeight;      // window height as set by the user (without IME squeeze)
    private boolean mAdjustingWindow;   // guards against reacting to our own layout changes

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public TermuxFloatView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAlpha(ALPHA_FOCUS);
        // Required so that View#onTouchEvent() handles gestures that are not consumed by a child:
        // the collapsed bubble and the drag/resize gestures rely on it.
        setClickable(true);
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
        // Load termux shared properties
        mProperties = new TermuxFloatAppSharedProperties(getContext());

        // Load termux float shared preferences
        // This will also fail if TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxFloatAppSharedPreferences.build(getContext(), true);
        if (mPreferences == null) {
            Logger.logError(LOG_TAG, "initFloatView: mPreferences is null, aborting");
            return false;
        }

        mTermuxFloatSessionClient = new TermuxFloatSessionClient(service, this);

        mTerminalView = findViewById(R.id.terminal_view);
        mTermuxFloatViewClient = new TermuxFloatViewClient(this, mTermuxFloatSessionClient);
        mTerminalView.setTerminalViewClient(mTermuxFloatViewClient);
        mTermuxFloatViewClient.initFloatView();

        // Set up the extra keys bar (Ctrl, Alt, Esc, arrows, etc.)
        mExtraKeysView = findViewById(R.id.extra_keys_view);
        if (mExtraKeysView != null) {
            mExtraKeysView.setExtraKeysViewClient(new FloatExtraKeysClient(mTerminalView));
            // Delay reload until after layout so GridLayout has a measured width.
            mExtraKeysView.post(this::setupExtraKeys);
        }

        initWindowControls();
        initSelectionBar();

        mFloatingBubbleManager = new FloatingBubbleManager(this);

        installKeyboardListener();

        return true;
    }

    private void initWindowControls() {
        mWindowControls = findViewById(R.id.window_controls);
        mWindowControls.setOnClickListener(v -> changeFocus(true));

        View minimizeButton = findViewById(R.id.minimize_button);
        if (minimizeButton != null)
            minimizeButton.setOnClickListener(v -> mFloatingBubbleManager.toggleBubble());

        View exitButton = findViewById(R.id.exit_button);
        if (exitButton != null)
            exitButton.setOnClickListener(v -> exit());
    }

    /**
     * The in-window text selection bar.
     *
     * <p>The system text selection {@code ActionMode} toolbar is unreliable inside a
     * {@code TYPE_APPLICATION_OVERLAY} window, so copy/paste must not depend on it. Long pressing
     * the terminal still starts the terminal's own selection mode (with its drag handles), and the
     * matching {@code copyModeChanged()} callback shows this bar, whose buttons act on the
     * selection directly.</p>
     */
    private void initSelectionBar() {
        mSelectionBar = findViewById(R.id.selection_bar);
        if (mSelectionBar == null) return;

        View copy = findViewById(R.id.selection_copy_button);
        if (copy != null) copy.setOnClickListener(v -> copySelectedText());

        View copyAll = findViewById(R.id.selection_copy_all_button);
        if (copyAll != null) copyAll.setOnClickListener(v -> copyAllText());

        View paste = findViewById(R.id.selection_paste_button);
        if (paste != null) paste.setOnClickListener(v -> {
            pasteFromClipboard();
            setSelectionBarVisible(false);
        });

        View done = findViewById(R.id.selection_close_button);
        if (done != null) done.setOnClickListener(v -> endSelectionMode());
    }

    /**
     * Configure the extra keys bar with the key set defined by {@link #EXTRA_KEYS_CONFIG}.
     */
    private void setupExtraKeys() {
        if (mExtraKeysView == null || mPreferences == null) return;
        try {
            ExtraKeysInfo extraKeysInfo = new ExtraKeysInfo(EXTRA_KEYS_CONFIG,
                    "default",
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            mExtraKeysView.reload(extraKeysInfo, mPreferences.getFontSize());
            shrinkExtraKeysLabels();
            mExtraKeysView.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            // Never let a broken extra keys config take the whole floating window down.
            Logger.logStackTraceWithMessage(LOG_TAG, "setupExtraKeys failed, hiding extra keys bar", e);
            mExtraKeysView.setVisibility(View.GONE);
        }
    }

    /**
     * Force a smaller label size on the extra keys than the terminal font size.
     *
     * <p>{@link ExtraKeysView#reload} sizes its buttons from the value it is given (the terminal
     * font size, 36 px here), which makes the labels overflow their ~74 px wide buttons.</p>
     */
    private void shrinkExtraKeysLabels() {
        if (mExtraKeysView == null) return;
        for (int i = 0; i < mExtraKeysView.getChildCount(); i++) {
            View child = mExtraKeysView.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                ((android.widget.TextView) child).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        EXTRA_KEYS_LABEL_SIZE_PX);
            }
        }
    }

    /**
     * Read the current state of a sticky extra keys button (CTRL/ALT/SHIFT/FN).
     *
     * <p>Required by {@link TermuxFloatViewClient} so that the sticky keys also apply to soft
     * keyboard input and hardware keys, exactly like the main Termux app does.</p>
     */
    public boolean readExtraKeysSpecialButton(SpecialButton specialButton) {
        if (mExtraKeysView == null) return false;
        Boolean state = mExtraKeysView.readSpecialButton(specialButton, true);
        return state != null && state;
    }

    /**
     * Custom extra-keys client that delegates normal keys to {@link TerminalExtraKeys}
     * but intercepts the clipboard/keyboard keys to handle them directly, since the system's
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
            if (key == null) return;

            if ("CPY".equalsIgnoreCase(key)) {
                copySelectedText();
                return;
            }
            if ("ALL".equalsIgnoreCase(key)) {
                copyAllText();
                return;
            }
            if ("PSTE".equalsIgnoreCase(key)) {
                pasteFromClipboard();
                return;
            }
            if ("KBRD".equalsIgnoreCase(key)) {
                toggleSoftKeyboard();
                return;
            }
            if (key.startsWith("0x") || key.startsWith("0X")) {
                sendControlCode(key);
                return;
            }
            mDelegate.onExtraKeyButtonClick(view, button, materialButton);
        }

        @Override
        public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton button, MaterialButton materialButton) {
            return mDelegate.performExtraKeyButtonHapticFeedback(view, button, materialButton);
        }
    }

    /** Send a raw code point such as 0x03 (Ctrl-C) to the current session. */
    private void sendControlCode(String key) {
        try {
            int codePoint = Integer.parseInt(key.substring(2), 16);
            TerminalSession session = mTerminalView.getCurrentSession();
            if (session != null) session.write(new String(Character.toChars(codePoint)));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "sendControlCode failed for " + key, e);
        }
    }

    /** Copy the currently selected terminal text, or the whole visible screen when nothing is selected. */
    private void copySelectedText() {
        try {
            String selected = mTerminalView.getSelectedText();
            if (selected == null || selected.isEmpty()) {
                // Fall back to the visible screen; an empty clipboard is the most common complaint.
                copyAllText();
                return;
            }
            setClipboard(selected);
            Logger.showToast(getContext(), getContext().getString(R.string.text_copied), false);
            endSelectionMode();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "copySelectedText failed", e);
        }
    }

    /** Copy the whole terminal screen (scrollback included) to the clipboard. */
    private void copyAllText() {
        try {
            String text = getTerminalText();
            if (text == null || text.trim().isEmpty()) {
                Logger.showToast(getContext(), getContext().getString(R.string.nothing_to_copy), false);
                return;
            }
            setClipboard(text);
            Logger.showToast(getContext(), getContext().getString(R.string.text_copied), false);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "copyAllText failed", e);
        }
    }

    private void setClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("termux-float", text));
    }

    /** Read the selection if selecting, otherwise the whole terminal screen. */
    private String getTerminalText() {
        if (mTerminalView == null) return null;
        String selected = mTerminalView.getSelectedText();
        if (selected != null && !selected.isEmpty()) return selected;
        // getSelectedText() only returns something while selection mode is active; the stored text
        // survives a trip through the "MORE" context menu.
        String stored = mTerminalView.getStoredSelectedText();
        if (stored != null && !stored.isEmpty()) return stored;
        return getScreenText();
    }

    /**
     * Extract everything currently on the terminal screen (visible rows, scrollback included).
     *
     * <p>{@code TerminalView.getText()} is private, so this rebuilds the same string through the
     * public {@code TerminalEmulator}/{@code TerminalBuffer} API, but trims the trailing blank
     * space that the fixed-width screen buffer is padded with.</p>
     */
    private String getScreenText() {
        try {
            TerminalEmulator emulator = mTerminalView.mEmulator;
            if (emulator == null || emulator.getScreen() == null) return null;
            String screen = emulator.getScreen().getSelectedText(0, 0, emulator.mColumns, emulator.mRows);
            if (screen == null) return null;
            StringBuilder sb = new StringBuilder();
            for (String line : screen.split("\n", -1)) {
                sb.append(line.replaceAll("\\s+$", "")).append('\n');
            }
            // Drop trailing empty lines.
            return sb.toString().replaceAll("\\s+$", "");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "getScreenText failed", e);
            return null;
        }
    }

    /**
     * Paste clipboard content into the terminal session.
     *
     * <p>Multi-line clipboard content is flattened to a single line first: pasting raw newlines
     * into a shell runs every line immediately, which is almost never what was intended when
     * pasting a snippet into a floating window.</p>
     */
    private void pasteFromClipboard() {
        try {
            String text = getClipboardText();
            if (text == null || text.isEmpty()) {
                Logger.showToast(getContext(), getContext().getString(R.string.clipboard_empty), false);
                return;
            }
            String singleLine = toSingleLine(text);
            TerminalEmulator emulator = mTerminalView.mEmulator;
            if (emulator != null) {
                emulator.paste(singleLine);
            } else {
                TerminalSession session = mTerminalView.getCurrentSession();
                if (session != null) session.write(singleLine);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "pasteFromClipboard failed", e);
        }
    }

    private String getClipboardText() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        return text == null ? null : text.toString();
    }

    private static String toSingleLine(String text) {
        if (text == null) return null;
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return text;
        return text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
    }

    /** Show/hide the in-window selection bar (called from {@link TermuxFloatViewClient}). */
    void setSelectionBarVisible(boolean visible) {
        if (mSelectionBar == null) return;
        // Make sure it is applied on the UI thread even if the callback came from another thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mSelectionBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        } else {
            mHandler.post(() -> mSelectionBar.setVisibility(visible ? View.VISIBLE : View.GONE));
        }
    }

    /** Leave the terminal's text selection mode and hide the selection bar. */
    private void endSelectionMode() {
        try {
            mTerminalView.stopTextSelectionMode();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "endSelectionMode failed", e);
        }
        setSelectionBarVisible(false);
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
        if (mExtraKeysView != null) mExtraKeysView.post(this::setupExtraKeys);
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

        removeKeyboardListener();

        if (mTermuxFloatSessionClient != null)
            mTermuxFloatSessionClient.onDetachedFromWindow();
    }

    @SuppressLint("RtlHardcoded")
    public void launchFloatingWindow() {
        layoutParams.flags = computeLayoutFlags(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.format = PixelFormat.RGBA_8888;

        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;

        int width = ViewGroup.LayoutParams.WRAP_CONTENT;
        int height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (mPreferences != null) {
            layoutParams.x = mPreferences.getWindowX();
            layoutParams.y = mPreferences.getWindowY();
            int w = mPreferences.getWindowWidth();
            int h = mPreferences.getWindowHeight();
            // If using the library default (500x500), scale to a sensible fraction of the screen.
            // Otherwise keep the saved size, only clamping it to the current display.
            if (w <= 500 && h <= 500) {
                android.util.DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
                w = (int) (dm.widthPixels * 0.9f);
                h = (int) (dm.heightPixels * 0.45f);
            }
            width = Math.min(Math.max(MIN_WINDOW_SIZE, w), DISPLAY_WIDTH > 0 ? DISPLAY_WIDTH : w);
            height = Math.min(Math.max(getMinWindowHeight(), h), DISPLAY_HEIGHT > 0 ? DISPLAY_HEIGHT : h);
        }
        layoutParams.width = width;
        layoutParams.height = height;

        mBaseWindowY = layoutParams.y;
        mBaseWindowHeight = layoutParams.height;

        mWindowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (getWindowToken() == null)
            mWindowManager.addView(this, layoutParams);
        showTouchKeyboard();
    }

    /** Smallest allowed window height; also accounts for the space reserved for the IME. */
    private int getMinWindowHeight() {
        return mImeVisible ? Math.max(MIN_IME_WINDOW_HEIGHT, mImeShift + 80) : MIN_WINDOW_SIZE;
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

        // Minimized bubble: handled in onTouchEvent(). It must not be consumed here, because a
        // window that intercepts from ACTION_DOWN receives all following events of the gesture in
        // onTouchEvent() and the tap/drag would never see its ACTION_UP.
        if (mFloatingBubbleManager != null && mFloatingBubbleManager.isMinimized()) {
            return false;
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
            if (clickedInside && !didClickInsideWindowControls(touchX, touchY) && !didClickInsideSelectionBar(touchX, touchY)) {
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

    private boolean didClickInsideSelectionBar(float touchX, float touchY) {
        if (mSelectionBar == null || mSelectionBar.getVisibility() != View.VISIBLE) return false;
        int[] barLoc = new int[2];
        mSelectionBar.getLocationOnScreen(barLoc);
        return (touchX >= barLoc[0] && touchX <= barLoc[0] + mSelectionBar.getWidth()) &&
                (touchY >= barLoc[1] && touchY <= barLoc[1] + mSelectionBar.getHeight());
    }

    void showTouchKeyboard() {
        if (mTerminalView == null) return;
        mTerminalView.post(() -> KeyboardUtils.showSoftKeyboard(getContext(), mTerminalView));
    }

    void hideTouchKeyboard() {
        if (mTerminalView == null) return;
        mTerminalView.post(() -> KeyboardUtils.hideSoftKeyboard(getContext(), mTerminalView));
    }

    /**
     * Show or hide the soft keyboard.
     *
     * <p>{@code KeyboardUtils.toggleSoftKeyboard()} from termux-shared does not work reliably from
     * an overlay window, so this checks the real IME state (tracked by the global layout listener)
     * and calls the appropriate {@link InputMethodManager} method directly.</p>
     */
    void toggleSoftKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            if (mImeVisible) {
                // An overlay window has no IME-accepting window token, so hideSoftInputFromWindow()
                // is silently ignored by most keyboards. Toggling with SHOW_FORCED does close an
                // already visible keyboard, and on the rare keyboard where it does not, hiding the
                // system keyboard with the back key still works.
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            } else {
                mTerminalView.requestFocus();
                imm.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
                // Some keyboards ignore SHOW_IMPLICIT for an overlay window; force it as a fallback.
                mHandler.postDelayed(() -> {
                    if (!mImeVisible) {
                        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                    }
                }, 400);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "toggleSoftKeyboard failed", e);
        }
    }

    void updateLongPressMode(boolean newValue) {
        isInLongPressState = newValue;
        if (mFloatingBubbleManager != null)
            mFloatingBubbleManager.updateLongPressBackgroundResource(isInLongPressState);
        setAlpha(newValue ? ALPHA_MOVING : (withFocus ? ALPHA_FOCUS : ALPHA_NOT_FOCUS));
        if (newValue && mFloatingBubbleManager != null && !mFloatingBubbleManager.isMinimized())
            Logger.showToast(getContext(), getContext().getString(R.string.after_long_press), false);
    }

    /**
     * Motion events should only be dispatched here when {@link #onInterceptTouchEvent(MotionEvent)} returns true.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Minimized bubble: drag it around, or tap to restore the floating window.
        if (mFloatingBubbleManager != null && mFloatingBubbleManager.isMinimized()) {
            return mFloatingBubbleManager.handleBubbleTouch(event);
        }

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
                    layoutParams.x = Math.min(Math.max(0, DISPLAY_WIDTH - layoutParams.width), Math.max(0, initialX + (int) (event.getRawX() - initialTouchX)));
                    layoutParams.y = Math.min(Math.max(0, DISPLAY_HEIGHT - layoutParams.height), Math.max(0, initialY + (int) (event.getRawY() - initialTouchY)));
                    mWindowManager.updateViewLayout(TermuxFloatView.this, layoutParams);
                    // The user moved the window: remember both the raw position and the shifted one.
                    if (mPreferences != null) {
                        mPreferences.setWindowX(layoutParams.x);
                        mPreferences.setWindowY(mImeVisible ? Math.max(0, layoutParams.y - mImeShift) : layoutParams.y);
                    }
                    mBaseWindowY = mImeVisible ? layoutParams.y - mImeShift : layoutParams.y;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    updateLongPressMode(false);
                    // Re-apply the IME shift for the new base position.
                    mBaseWindowHeight = mImeVisible ? Math.max(MIN_WINDOW_SIZE, layoutParams.height + mImeShift) : layoutParams.height;
                    if (mImeVisible) adjustForIme(true);
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
        if (mFloatingBubbleManager != null && mFloatingBubbleManager.isMinimized()) {
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

    /** Update the remembered user-chosen window position (used when the bubble is dragged). */
    void setBaseWindowPosition(int x, int y) {
        initialX = x;
        mBaseWindowY = Math.max(0, y - mImeShift);
        initialY = mBaseWindowY;
    }

    public void closeFloatingWindow() {
        removeKeyboardListener();
        endSelectionMode();
        if (getWindowToken() != null)
            mWindowManager.removeView(this);

        if (mFloatingBubbleManager != null) {
            mFloatingBubbleManager.cleanup();
            mFloatingBubbleManager = null;
        }
    }

    private void exit() {
        Intent exitIntent = new Intent(getContext(), TermuxFloatService.class).setAction(TermuxConstants.TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE.ACTION_STOP_SERVICE);
        getContext().startService(exitIntent);
    }

    // ---------------------------------------------------------------------------------------
    // Soft keyboard (IME) handling
    //
    // The floating window is an overlay, so the IME is drawn *on top of* it instead of pushing
    // it up like it does for a normal activity. Without correction the bottom of the window
    // (terminal rows and the extra keys bar) would simply be hidden behind the keyboard.
    // The window is therefore shifted up and squeezed into the space that is left above the IME.
    // ---------------------------------------------------------------------------------------

    private void installKeyboardListener() {
        if (mGlobalLayoutListener != null) return;
        mGlobalLayoutListener = this::onGlobalLayoutForIme;
        getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayoutListener);
    }

    private void removeKeyboardListener() {
        if (mGlobalLayoutListener == null) return;
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer != null && observer.isAlive())
            observer.removeOnGlobalLayoutListener(mGlobalLayoutListener);
        mGlobalLayoutListener = null;
    }

    private void onGlobalLayoutForIme() {
        if (mAdjustingWindow || isResizing || isInLongPressState) return;
        if (mFloatingBubbleManager != null && mFloatingBubbleManager.isMinimized()) return;

        if (DISPLAY_WIDTH <= 0 || DISPLAY_HEIGHT <= 0) updateDisplaySize();

        android.graphics.Rect visibleFrame = new android.graphics.Rect();
        getWindowVisibleDisplayFrame(visibleFrame);
        int visibleFrameHeight = visibleFrame.height();
        int covered = DISPLAY_HEIGHT - visibleFrameHeight;
        boolean imeVisible = covered > Math.max(1, (int) (DISPLAY_HEIGHT * IME_MIN_FRACTION));

        if (imeVisible == mImeVisible) {
            if (imeVisible) {
                // Keyboard size can change (e.g. suggestion bar toggled): re-apply if it differs.
                int newShift = covered;
                if (Math.abs(newShift - mImeShift) > 8) {
                    mImeShift = newShift;
                    adjustForIme(true);
                }
            }
            return;
        }

        mImeVisible = imeVisible;
        mImeShift = imeVisible ? covered : 0;
        adjustForIme(imeVisible);
    }

    /** Shift and squeeze the window so that it stays above the soft keyboard. */
    private void adjustForIme(boolean imeVisible) {
        if (layoutParams == null || mWindowManager == null || getWindowToken() == null) return;

        // Remember the user's own window geometry while the keyboard is closed.
        if (!imeVisible && !mImeVisible) {
            mBaseWindowY = layoutParams.y;
            mBaseWindowHeight = layoutParams.height;
        }

        int newHeight;
        int newY;
        if (imeVisible) {
            // Slide the window up so its bottom edge (the extra keys bar) sits just above the
            // keyboard, and keep the height the user chose. Squeezing the window instead makes the
            // terminal reflow into one row too few, and its last line then ends up hidden behind
            // the extra keys bar.
            newHeight = mBaseWindowHeight;
            int keyboardTop = Math.max(0, DISPLAY_HEIGHT - mImeShift - IME_CLEARANCE_PX);
            newY = keyboardTop - 8 - newHeight;
            if (newY < 0) {
                // Not enough room above the keyboard for the whole window: keep it on screen and
                // let its bottom be covered by the IME, like a normal (non overlay) app.
                newY = 0;
            }
        } else {
            newHeight = mBaseWindowHeight;
            newY = mBaseWindowY;
        }

        if (layoutParams.height == newHeight && layoutParams.y == newY) return;

        mAdjustingWindow = true;
        try {
            layoutParams.height = newHeight;
            layoutParams.y = newY;
            mWindowManager.updateViewLayout(this, layoutParams);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "adjustForIme failed", e);
        } finally {
            // Layout callbacks are posted, so clear the guard on the next main thread pass.
            mHandler.post(() -> mAdjustingWindow = false);
        }
    }

    // ---------------------------------------------------------------------------------------

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

    /** Get the extra keys view, or {@code null} before {@link #initFloatView} ran. */
    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    /** Glyph shown inside the collapsed bubble (the terminal view is hidden then). */
    public View getBubbleIcon() {
        return findViewById(R.id.bubble_icon);
    }

    public void reloadViewStyling() {
        // Leaving here for future support for termux-reload-settings
        if (mTermuxFloatSessionClient != null)
            mTermuxFloatSessionClient.onReload();
    }
}
