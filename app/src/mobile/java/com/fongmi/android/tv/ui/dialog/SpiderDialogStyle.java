package com.fongmi.android.tv.ui.dialog;

import android.view.WindowManager;
import android.content.Context;
import com.google.android.material.button.MaterialButton;
import androidx.appcompat.app.AlertDialog;

/** Mobile inherits its Material dialog theme and resizes for the input method. */
final class SpiderDialogStyle {
    static MaterialButton copyButton(Context context) {
        return new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    }

    static void apply(AlertDialog dialog) {
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
}
