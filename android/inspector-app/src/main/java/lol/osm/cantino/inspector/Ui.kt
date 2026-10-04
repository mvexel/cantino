package lol.osm.cantino.inspector

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/*
 * Tiny programmatic-UI helpers, as in android/cafe-app Ui.kt: views in code,
 * no XML, no Compose, no AppCompat; the app depends on Cantino and MapLibre only.
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

fun Context.mono(value: CharSequence, color: Int = Colors.TEXT): TextView =
    text(value, 13f, color = color).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }

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

fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

object Colors {
    val TEXT = Color.rgb(0x21, 0x21, 0x21)
    val MUTED = Color.rgb(0x6b, 0x6b, 0x6b)
    val ERROR = Color.rgb(0xc6, 0x28, 0x28)
    val LINK = Color.rgb(0x15, 0x65, 0xc0)
    /** Query results on the map. */
    val RESULT = Color.rgb(0xff, 0x6d, 0x00)
    /** The selected object: a strong colour that the light basemap never uses. */
    val HIGHLIGHT = Color.rgb(0xd5, 0x00, 0xf9)
    val HIT = Color.rgb(0x2e, 0x7d, 0x32)

    fun hex(color: Int) = "#%06x".format(color and 0xffffff)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.ROOT, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}

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
