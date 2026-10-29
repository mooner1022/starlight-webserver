package dev.mooner.starlightwebserver.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import kotlin.math.roundToInt

/**
 * Looks of StarLight's own widgets. The plugin can't reference the app's resources, so they are looked up by name in
 * the app's package, with the app's values as fallbacks.
 */
internal enum class HostColor(val resName: String, val light: Int, val dark: Int) {
    Text("text", 0xFF4C4C4C.toInt(), 0xFFD5D5D5.toInt()),
    TextSub("text_sub", 0xFFA3A3A3.toInt(), 0xFF9E9E9E.toInt()),
    Accent("main_dark", 0xFFAD977A.toInt(), 0xFFAD977A.toInt()),
    Ok("status_ok", 0xFF7ACA8A.toInt(), 0xFF7ACA8A.toInt()),
    Warning("status_warning", 0xFFF2B05E.toInt(), 0xFFF2B05E.toInt()),
    Error("status_error", 0xFFFF6188.toInt(), 0xFFFF6188.toInt()),
}

@SuppressLint("DiscouragedApi")
internal fun Context.hostColor(color: HostColor): Int {
    val id = resources.getIdentifier(color.resName, "color", packageName)
    if (id != 0)
        return ContextCompat.getColor(this, id)
    val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return if (isNight) color.dark else color.light
}

@SuppressLint("DiscouragedApi")
internal fun Context.hostFont(bold: Boolean): Typeface? {
    val name = if (bold) "nanumsquare_round_bold" else "nanumsquare_round_regular"
    val id = resources.getIdentifier(name, "font", packageName)
    return if (id == 0) null else runCatching { ResourcesCompat.getFont(this, id) }.getOrNull()
}

@SuppressLint("DiscouragedApi")
internal fun Context.hostDimen(name: String, fallbackDp: Int): Int {
    val id = resources.getIdentifier(name, "dimen", packageName)
    return if (id == 0) dp(fallbackDp) else resources.getDimensionPixelSize(id)
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

internal fun Context.createText(
    text: CharSequence = "",
    sizeSp: Float,
    color: HostColor,
    bold: Boolean = true,
): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        hostFont(bold)?.let { typeface = it }
        setTextColor(hostColor(color))
    }

internal fun Context.createIcon(@DrawableRes icon: Int, color: HostColor, sizeDp: Int): ImageView =
    ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (icon != 0 && icon != -1)
            setImageResource(icon)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(hostColor(color)))
    }

/** A small filled circle, for the server state. */
internal fun Context.createDot(sizeDp: Int): View =
    View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
    }

internal fun View.setDotColor(color: Int) {
    (background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
}

internal fun Context.createRow(title: String): Pair<LinearLayout, TextView> {
    val value = createText(sizeSp = 13f, color = HostColor.Text).apply {
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        }
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
        addView(createText(title, sizeSp = 13f, color = HostColor.TextSub, bold = false))
        addView(value)
    }
    return row to value
}

internal fun View.setRipple() {
    val outValue = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
    setBackgroundResource(outValue.resourceId)
}

internal const val ROW_HEIGHT_DP = 24
