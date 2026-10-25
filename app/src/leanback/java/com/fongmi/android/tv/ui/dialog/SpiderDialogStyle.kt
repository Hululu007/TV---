package com.fongmi.android.tv.ui.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor
import com.fongmi.android.tv.ui.custom.JetStreamDialogButton
import com.fongmi.android.tv.ui.custom.applyJetStreamCursor
import com.fongmi.android.tv.ui.custom.applyJetStreamTypeface
import com.fongmi.android.tv.ui.custom.bindJetStreamTheme
import com.fongmi.android.tv.ui.custom.jetStreamColor
import com.fongmi.android.tv.ui.custom.jetStreamColorStateList
import com.fongmi.android.tv.ui.custom.jetStreamDp
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/** The shared source form uses the same palette, typeface and focus treatment as TV sheets. */
object SpiderDialogStyle {
    @JvmStatic
    fun copyButton(context: Context): MaterialButton = JetStreamDialogButton(context)

    @JvmStatic
    fun apply(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val decor = window.decorView
        window.setWindowAnimations(R.style.JetStreamDialogAnim)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout((decor.resources.displayMetrics.widthPixels * 0.68f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        val background = GradientDrawable().apply { cornerRadius = decor.jetStreamDp(28) }
        decor.bindJetStreamTheme { background.setColor(decor.jetStreamColor(R.color.jetstream_surface)) }
        window.setBackgroundDrawable(background)
        style(decor)
        JetStreamDialogDecor.tintButtons(dialog)
    }

    private fun style(view: View) {
        if (view is TextView) {
            view.applyJetStreamTypeface()
            view.applyJetStreamCursor()
            if (view !is MaterialButton) view.bindJetStreamTheme {
                view.setTextColor(view.jetStreamColorStateList(R.color.jetstream_on_surface))
                view.setHintTextColor(view.jetStreamColor(R.color.jetstream_on_surface_variant))
            }
        }
        if (view is TextInputLayout) view.bindJetStreamTheme {
            view.defaultHintTextColor = view.jetStreamColorStateList(R.color.jetstream_on_surface_variant)
            view.setBoxStrokeColorStateList(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(view.jetStreamColor(R.color.jetstream_outline), view.jetStreamColor(R.color.jetstream_primary), view.jetStreamColor(R.color.jetstream_on_surface_variant))
            ))
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
    }
}
