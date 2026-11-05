package io.github.meiyongai.toki.subtitle.hook

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import io.github.meiyongai.toki.subtitle.R

/** Styles only module-owned dialogs. Never installs a global host dialog/view hook. */
internal object TokiDialogs {
    fun builder(context: Context): AlertDialog.Builder {
        val themed = ContextThemeWrapper(context, R.style.Theme_Toki_EmbeddedDialog)
        return object : AlertDialog.Builder(themed) {
            override fun create(): AlertDialog = super.create().also { dialog ->
                dialog.window?.setGravity(Gravity.BOTTOM)
                dialog.setOnShowListener {
                    val density = themed.resources.displayMetrics.density
                    val accent = themed.getColor(R.color.toki_ui_accent)
                    val secondary = themed.getColor(R.color.toki_ui_secondary)
                    for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                        val button = dialog.getButton(which) ?: continue
                        button.isAllCaps = false; button.textSize = 14f
                        button.minHeight = (48 * density).toInt()
                        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        if (which == AlertDialog.BUTTON_POSITIVE) {
                            button.background = rounded(accent, 12 * density)
                            button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(secondary, themed.getColor(R.color.toki_ui_on_accent))))
                            button.setPadding((16*density).toInt(), 0, (16*density).toInt(), 0)
                        } else button.setTextColor(accent)
                    }
                }
            }
        }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }
    fun segmented(group: RadioGroup) {
        val context = group.context; val density = context.resources.displayMetrics.density
        val ink = context.getColor(R.color.toki_ui_ink)
        val secondary = context.getColor(R.color.toki_ui_secondary)
        group.orientation = RadioGroup.HORIZONTAL
        group.background = rounded(context.getColor(R.color.toki_ui_canvas), 12 * density)
        val padding = (4 * density).toInt(); group.setPadding(padding, padding, padding, padding)
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i) as? RadioButton ?: continue
            button.buttonDrawable = null
            button.gravity = Gravity.CENTER; button.textSize = 12f
            button.minWidth = 0; button.minimumWidth = 0; button.minHeight = (40 * density).toInt()
            button.setPadding(padding, padding, padding, padding)
            button.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ink, secondary)))
            button.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), rounded(context.getColor(R.color.toki_ui_surface), 9*density))
                addState(intArrayOf(), rounded(Color.TRANSPARENT, 9*density))
            }
            button.layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
    }
}
