package io.github.mvexel.cantino.cafe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime

/*
 * Tiny programmatic-UI helpers. The app builds its views in code (no XML,
 * no Compose, no AppCompat) to keep the reference app's dependency surface to
 * the framework plus MapLibre.
 */

fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.text(value: CharSequence, sizeSp: Float = 15f, bold: Boolean = false, color: Int = Colors.TEXT): TextView =
    TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.button(label: String, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

fun Context.vertical(paddingDp: Int = 16, vararg children: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        children.forEach { addView(it) }
    }

fun Context.horizontal(vararg children: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { addView(it) }
    }

fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

object Colors {
    val TEXT = Color.rgb(0x21, 0x21, 0x21)
    val MUTED = Color.rgb(0x6b, 0x6b, 0x6b)
    val OPEN = Color.rgb(0x2e, 0x7d, 0x32)
    val CLOSED = Color.rgb(0xc6, 0x28, 0x28)
    val UNKNOWN = Color.rgb(0x8a, 0x8a, 0x8a)
    val ME = Color.rgb(0x15, 0x65, 0xc0)
    val LINK = Color.rgb(0x15, 0x65, 0xc0)

    fun of(state: OpenState) = when (state) {
        OpenState.Open -> OPEN
        OpenState.Closed -> CLOSED
        is OpenState.Unknown -> UNKNOWN
    }

    fun hex(color: Int) = "#%06x".format(color and 0xffffff)
}

/** Short user-facing label. Unknown is always spelled out, never shown as closed. */
fun OpenState.label(): String = when (this) {
    OpenState.Open -> "Open now"
    OpenState.Closed -> "Closed now"
    is OpenState.Unknown -> "Hours unknown"
}

fun OutdoorSeating.label(): String = when (this) {
    OutdoorSeating.Yes -> "Outdoor seating: yes"
    OutdoorSeating.No -> "Outdoor seating: no"
    is OutdoorSeating.Unknown -> if (raw == null) "Outdoor seating: unknown" else "Outdoor seating: unknown (\"$raw\")"
}

/** "● Open now" with a coloured bullet. */
fun coloredStatus(state: OpenState): CharSequence = SpannableStringBuilder().apply {
    append("● ", ForegroundColorSpan(Colors.of(state)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    append(state.label())
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}

fun now(): LocalDateTime = LocalDateTime.now()

/** Dark status/navigation bar icons on the app's white screens (edge-to-edge since targetSdk 35). */
fun android.app.Activity.useLightSystemBars() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light, light)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
}
