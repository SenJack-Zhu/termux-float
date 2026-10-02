package com.termux.window;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.termux.shared.view.ViewUtils;

/**
 * Handles displaying our TermuxFloatView as a collapsed bubble and restoring back
 * to its original display.
 *
 * <p>The bubble is a small circular window that can be dragged around and tapped to restore the
 * floating terminal. The terminal view itself is made fully transparent while collapsed (rather
 * than removed) so that the terminal session keeps updating in the background and the window can
 * be restored without re-attaching it.</p>
 */
public class FloatingBubbleManager {
    private static final int DEFAULT_BUBBLE_SIZE_DP = 56;

    private TermuxFloatView mTermuxFloatView;
    private final int BUBBLE_SIZE_PX;

    private boolean mIsMinimized;

    // preserve original layout values so we can restore to normal window
    // from our bubble
    private int mOriginalLayoutWidth;
    private int mOriginalLayoutHeight;
    private Drawable mOriginalFloatViewBackground;

    /** The user-chosen window size, i.e. the size before the last minimize. */
    private int mWindowWidth;
    private int mWindowHeight;

    public FloatingBubbleManager(TermuxFloatView termuxFloatView) {
        mTermuxFloatView = termuxFloatView;
        BUBBLE_SIZE_PX = (int) ViewUtils.dpToPx(mTermuxFloatView.getContext(), DEFAULT_BUBBLE_SIZE_DP);
    }

    public void toggleBubble() {
        if (isMinimized()) {
            displayAsFloatingWindow();
        } else {
            displayAsFloatingBubble();
        }
    }

    public void updateLongPressBackgroundResource(boolean isInLongPressState) {
        if (isMinimized()) {
            return;
        }
        mTermuxFloatView.setBackgroundResource(isInLongPressState ? R.drawable.floating_window_background_resize : R.drawable.floating_window_background);
    }

    public void displayAsFloatingBubble() {
        WindowManager.LayoutParams layoutParams = getLayoutParams();

        // Remember the window geometry the user set, so restoring does not depend on whichever
        // values happen to be in the layout params at minimize time.
        mWindowWidth = layoutParams.width;
        mWindowHeight = layoutParams.height;
        mOriginalLayoutWidth = mWindowWidth;
        mOriginalLayoutHeight = mWindowHeight;
        mOriginalFloatViewBackground = mTermuxFloatView.getBackground();

        layoutParams.width = BUBBLE_SIZE_PX;
        layoutParams.height = BUBBLE_SIZE_PX;

        // Make the bubble round and opaque. A shape drawable background provides a rectangular
        // outline, so clipping to it would still look like a square; the circular background
        // drawable is used for looks while the terminal view is hidden behind it.
        mTermuxFloatView.setBackgroundResource(R.drawable.round_button_with_outline);
        mTermuxFloatView.setClipToOutline(true);

        View terminalView = mTermuxFloatView.getTerminalView();
        if (terminalView != null) {
            // GONE rather than a transparent view: Android does not deliver touches to a view whose
            // alpha is 0, so a merely invisible TerminalView would swallow the bubble's taps and the
            // bubble could never be dragged or restored. The terminal session keeps running, only
            // its rendering stops while collapsed.
            terminalView.setAlpha(0f);
            terminalView.setVisibility(View.GONE);
        }

        // Show a small terminal glyph inside the bubble instead of the hidden terminal.
        View bubbleIcon = mTermuxFloatView.getBubbleIcon();
        if (bubbleIcon != null) bubbleIcon.setVisibility(View.VISIBLE);

        // The extra keys bar and the control bar have no meaning in a 56 dp bubble, and would be
        // squeezed into unreadable slivers; hide them while collapsed.
        ViewGroup windowControls = mTermuxFloatView.findViewById(R.id.window_controls);
        if (windowControls != null) windowControls.setVisibility(View.GONE);

        View extraKeys = mTermuxFloatView.getExtraKeysView();
        if (extraKeys != null) extraKeys.setVisibility(View.GONE);

        View selectionBar = mTermuxFloatView.findViewById(R.id.selection_bar);
        if (selectionBar != null) selectionBar.setVisibility(View.GONE);

        mTermuxFloatView.hideTouchKeyboard();
        mTermuxFloatView.changeFocus(false);

        getWindowManager().updateViewLayout(mTermuxFloatView, layoutParams);
        mIsMinimized = true;
    }

    public void displayAsFloatingWindow() {
        WindowManager.LayoutParams layoutParams = getLayoutParams();

        // restore back to previous values
        layoutParams.width = mOriginalLayoutWidth > 0 ? mOriginalLayoutWidth : mWindowWidth;
        layoutParams.height = mOriginalLayoutHeight > 0 ? mOriginalLayoutHeight : mWindowHeight;

        View terminalView = mTermuxFloatView.getTerminalView();
        if (terminalView != null) {
            terminalView.setAlpha(1f);
            terminalView.setVisibility(View.VISIBLE);
            terminalView.setClipToOutline(false);
        }

        View bubbleIcon = mTermuxFloatView.getBubbleIcon();
        if (bubbleIcon != null) bubbleIcon.setVisibility(View.GONE);

        mTermuxFloatView.setBackground(mOriginalFloatViewBackground != null
                ? mOriginalFloatViewBackground
                : mTermuxFloatView.getResources().getDrawable(R.drawable.floating_window_background));
        mTermuxFloatView.setClipToOutline(false);

        ViewGroup windowControls = mTermuxFloatView.findViewById(R.id.window_controls);
        if (windowControls != null) windowControls.setVisibility(View.VISIBLE);

        View extraKeys = mTermuxFloatView.getExtraKeysView();
        if (extraKeys != null) extraKeys.setVisibility(View.VISIBLE);

        getWindowManager().updateViewLayout(mTermuxFloatView, layoutParams);
        mIsMinimized = false;
    }

    public boolean isMinimized() {
        return mIsMinimized;
    }

    /**
     * Handling for touches while collapsed: drag the bubble around, or restore the window on tap.
     *
     * @return {@code true} if the gesture was handled by the bubble.
     */
    public boolean handleBubbleTouch(android.view.MotionEvent event) {
        WindowManager.LayoutParams layoutParams = getLayoutParams();
        switch (event.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mDragStartRawX = event.getRawX();
                mDragStartRawY = event.getRawY();
                mDragStartX = layoutParams.x;
                mDragStartY = layoutParams.y;
                mDragged = false;
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                int dx = (int) (event.getRawX() - mDragStartRawX);
                int dy = (int) (event.getRawY() - mDragStartRawY);
                if (Math.abs(dx) > mTouchSlop || Math.abs(dy) > mTouchSlop) mDragged = true;
                if (!mDragged) return true;

                Rect bounds = getDisplayBounds();
                layoutParams.x = Math.min(Math.max(bounds.left, mDragStartX + dx), Math.max(bounds.left, bounds.right - layoutParams.width));
                layoutParams.y = Math.min(Math.max(bounds.top, mDragStartY + dy), Math.max(bounds.top, bounds.bottom - layoutParams.height));
                getWindowManager().updateViewLayout(mTermuxFloatView, layoutParams);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
                if (mDragged) {
                    // Keep the new position so the bubble stays where it was dropped.
                    if (mTermuxFloatView.getPreferences() != null) {
                        mTermuxFloatView.getPreferences().setWindowX(layoutParams.x);
                        mTermuxFloatView.getPreferences().setWindowY(layoutParams.y);
                    }
                    mTermuxFloatView.setBaseWindowPosition(layoutParams.x, layoutParams.y);
                } else {
                    displayAsFloatingWindow();
                    mTermuxFloatView.changeFocus(true);
                    mTermuxFloatView.showTouchKeyboard();
                }
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                return true;
        }
        return false;
    }

    private float mDragStartRawX, mDragStartRawY;
    private int mDragStartX, mDragStartY;
    private boolean mDragged;
    private static final int mTouchSlop = 16;

    private Rect getDisplayBounds() {
        Rect bounds = new Rect(0, 0, 0, 0);
        WindowManager wm = getWindowManager();
        if (wm != null) {
            android.util.DisplayMetrics metrics = mTermuxFloatView.getResources().getDisplayMetrics();
            bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm.getCurrentWindowMetrics() != null) {
                Rect wmBounds = wm.getCurrentWindowMetrics().getBounds();
                if (wmBounds.width() > 0) bounds.set(wmBounds);
            }
        }
        return bounds;
    }

    public void cleanup() {
        mTermuxFloatView = null;
        mOriginalFloatViewBackground = null;
    }

    private TermuxFloatView getTermuxFloatView() {
        return mTermuxFloatView;
    }

    private WindowManager getWindowManager() {
        return mTermuxFloatView.mWindowManager;
    }

    private WindowManager.LayoutParams getLayoutParams() {
        return (WindowManager.LayoutParams) mTermuxFloatView.getLayoutParams();
    }
}
