package com.example.localvoice;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

/** A button that keeps click accessibility while its touch listener handles holding. */
public final class HoldToTalkButton extends Button {
    public HoldToTalkButton(Context context) {
        super(context);
    }

    public HoldToTalkButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HoldToTalkButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
