package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.EnumMap
import java.util.WeakHashMap

/** Only observed main windows/toolbar roots. Native setter intent remains the restoration baseline. */
internal class ImmersiveWindowBindings(private val report: (Exception) -> Unit = {},
    private val beforeWrite: (Property) -> Unit = {}) : AutoCloseable {
    enum class Property { STATUS, NAVIGATION, STATUS_CONTRAST, NAVIGATION_CONTRAST, CUTOUT, FITS, FLAGS }
    data class Result(val known: Int, val writes: Int, val restored: Int, val failures: Int)
    private class Row(activity: Activity, val baseline: EnumMap<Property, Any>) {
        val activity = WeakReference(activity)
        val owned = mutableSetOf<Property>()
        var prepared = false
    }
    private class Background(view: View, var baseline: Drawable?) {
        val view = WeakReference(view)
        var owned = false
        var listener: View.OnAttachStateChangeListener? = null
    }
    private val windows = WeakHashMap<Window, Row>()
    private val backgrounds = WeakHashMap<View, Background>()
    private var writing = 0
    private var enabled = false
    private var closed = false
    private var failures = 0
    private var writes = 0
    private var restored = 0
    companion object {
        @Suppress("DEPRECATION")
        internal val LAYOUT_MASK = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    fun observe(activity: Activity) {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        val window = activity.window
        if (window !in windows) {
            if (windows.size >= 12) { failed(IllegalStateException("Window observation capacity reached")); return }
            try {
                // Capture every supported baseline before the first write; never guess decor-fits=true.
                val baseline = EnumMap<Property, Any>(Property::class.java)
                properties().forEach { property ->
                    try { baseline[property] = read(window, property) }
                    catch (error: Exception) { throw ImmersiveFailure.wrap(ImmersiveFailure.Phase.CAPTURE,
                        ImmersiveFailure.Field.valueOf(property.name), error) }
                }
                windows[window] = Row(activity, baseline)
            } catch (error: Exception) { failed(error); return }
        }
        apply(window, checkNotNull(windows[window]))
    }
    fun background(view: View) {
        if (closed) return
        var row = backgrounds[view]
        if (row == null) {
            if (backgrounds.size >= 24) { failed(IllegalStateException("Background observation capacity reached"), field = ImmersiveFailure.Field.BACKGROUND); return }
            row = Background(view, view.background)
            backgrounds[view] = row
            row.listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { releaseBackground(v) }
            }.also(view::addOnAttachStateChangeListener)
        }
        applyBackground(view, row)
    }
    fun configure(value: Boolean): Result {
        enabled = value && !closed
        windows.entries.toList().forEach { (window, row) -> apply(window, row) }
        backgrounds.entries.toList().forEach { (view, row) -> applyBackground(view, row) }
        return result()
    }
    fun result() = Result(windows.size + backgrounds.size, writes, restored, failures)
    fun native(window: Window, property: Property, input: Any): Any {
        val row = windows[window] ?: return input
        if (closed || writing != 0 || property !in row.baseline) return input
        row.baseline[property] = if (property == Property.FLAGS && property in row.owned &&
            (input as Int) and LAYOUT_MASK == LAYOUT_MASK)
            (input and LAYOUT_MASK.inv()) or ((row.baseline.getValue(property) as Int) and LAYOUT_MASK) else input
        return if (enabled && property in row.owned) imposed(property, input) else input
    }
    fun nativeFlags(view: View, value: Int): Int {
        if (writing != 0 || closed) return value
        val window = windows.keys.firstOrNull { it.decorView === view } ?: return value
        return native(window, Property.FLAGS, value) as Int
    }
    fun nativeAttributes(window: Window, value: WindowManager.LayoutParams): WindowManager.LayoutParams {
        if (writing != 0 || closed || window !in windows) return value
        val row = checkNotNull(windows[window])
        val cutout = if (Property.CUTOUT in row.owned && value.layoutInDisplayCutoutMode ==
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) value.layoutInDisplayCutoutMode
            else native(window, Property.CUTOUT, value.layoutInDisplayCutoutMode) as Int
        if (cutout == value.layoutInDisplayCutoutMode) return value
        return WindowManager.LayoutParams().also { it.copyFrom(value); it.layoutInDisplayCutoutMode = cutout }
    }
    fun nativeBackground(view: View, input: Drawable?): Drawable? {
        val row = backgrounds[view] ?: return input
        if (writing != 0 || closed) return input
        row.baseline = input
        return if (enabled && row.owned) null else input
    }
    private fun properties() = Property.entries.filter {
        (Build.VERSION.SDK_INT >= 29 || it !in setOf(Property.STATUS_CONTRAST, Property.NAVIGATION_CONTRAST)) &&
            (Build.VERSION.SDK_INT >= 30 || it != Property.FITS)
    }
    private fun imposed(property: Property, baseline: Any): Any = when (property) {
        Property.STATUS, Property.NAVIGATION -> Color.TRANSPARENT
        Property.STATUS_CONTRAST, Property.NAVIGATION_CONTRAST, Property.FITS -> false
        Property.CUTOUT -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        Property.FLAGS -> (baseline as Int) or LAYOUT_MASK
    }
    @Suppress("DEPRECATION")
    private fun read(window: Window, property: Property): Any = when (property) {
        Property.STATUS -> window.statusBarColor
        Property.NAVIGATION -> window.navigationBarColor
        Property.STATUS_CONTRAST -> window.isStatusBarContrastEnforced
        Property.NAVIGATION_CONTRAST -> window.isNavigationBarContrastEnforced
        Property.CUTOUT -> window.attributes.layoutInDisplayCutoutMode
        Property.FITS -> window.javaClass.getMethod("decorFitsSystemWindows").also {
            check(it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0)
        }.invoke(window) as Boolean
        Property.FLAGS -> window.decorView.systemUiVisibility
    }
    @Suppress("DEPRECATION")
    private fun write(window: Window, property: Property, value: Any) {
        writing++
        try {
            beforeWrite(property)
            when (property) {
                Property.STATUS -> window.statusBarColor = value as Int
                Property.NAVIGATION -> window.navigationBarColor = value as Int
                Property.STATUS_CONTRAST -> window.isStatusBarContrastEnforced = value as Boolean
                Property.NAVIGATION_CONTRAST -> window.isNavigationBarContrastEnforced = value as Boolean
                Property.CUTOUT -> window.attributes = WindowManager.LayoutParams().also {
                    it.copyFrom(window.attributes); it.layoutInDisplayCutoutMode = value as Int
                }
                Property.FITS -> window.setDecorFitsSystemWindows(value as Boolean)
                Property.FLAGS -> window.decorView.systemUiVisibility = value as Int
            }
        } finally { writing-- }
    }
    private fun apply(window: Window, row: Row) {
        val beforeWrites = writes; val beforeRestored = restored
        val captured = EnumMap<Property, Any>(Property::class.java)
        if (enabled) {
            // Read all fields before any write: a decor-fits setter may also change legacy layout flags.
            for (property in row.baseline.keys.toList()) try { captured[property] = read(window, property) }
            catch (error: Exception) { failed(error, ImmersiveFailure.Phase.CAPTURE, ImmersiveFailure.Field.valueOf(property.name)) }
            for ((property, current) in captured) {
                val before = row.baseline.getValue(property)
                if (!row.prepared || current != imposed(property, before)) {
                    row.baseline[property] = if (property == Property.FLAGS && row.prepared &&
                        ((current as Int) and LAYOUT_MASK) == LAYOUT_MASK)
                        (current and LAYOUT_MASK.inv()) or ((before as Int) and LAYOUT_MASK) else current
                }
            }
            row.prepared = true
        }
        for (property in row.baseline.keys.toList()) try {
            if (enabled && property !in captured) continue
            val current = read(window, property)
            val baseline = row.baseline.getValue(property)
            val target = imposed(property, baseline)
            if (enabled) {
                val desired = if (property == Property.FLAGS) ((current as Int) and LAYOUT_MASK.inv()) or LAYOUT_MASK else target
                if (current != desired) { write(window, property, desired); writes++ }
                row.owned += property
            } else if (property in row.owned) {
                val restoredValue = if (property == Property.FLAGS)
                    ((current as Int) and LAYOUT_MASK.inv()) or ((baseline as Int) and LAYOUT_MASK)
                else if (current == target) baseline else current
                if (current != restoredValue) { write(window, property, restoredValue); restored++ }
                row.owned -= property
            }
        } catch (error: Exception) { failed(error, if (enabled) ImmersiveFailure.Phase.APPLY else ImmersiveFailure.Phase.RESTORE,
            ImmersiveFailure.Field.valueOf(property.name)) }
        if (!enabled && row.owned.isEmpty()) row.prepared = false
        if (writes > beforeWrites || restored > beforeRestored) try { window.decorView.requestApplyInsets() }
        catch (error: Exception) { failed(error, ImmersiveFailure.Phase.WINDOW_REFRESH) }
    }
    private fun applyBackground(view: View, row: Background) {
        try {
            if (enabled) {
                if (!row.owned || view.background != null) row.baseline = view.background
                if (view.background != null) { changing { view.background = null }; writes++ }
                row.owned = true
            } else if (row.owned) {
                if (view.background == null && row.baseline != null) {
                    changing { view.background = row.baseline }; restored++
                }
                row.owned = false
            }
        } catch (error: Exception) { failed(error, if (enabled) ImmersiveFailure.Phase.BACKGROUND_APPLY else ImmersiveFailure.Phase.BACKGROUND_RESTORE,
            ImmersiveFailure.Field.BACKGROUND) }
    }
    private inline fun changing(block: () -> Unit) { writing++; try { block() } finally { writing-- } }
    private fun failed(error: Exception, phase: ImmersiveFailure.Phase = ImmersiveFailure.Phase.CAPTURE,
        field: ImmersiveFailure.Field = ImmersiveFailure.Field.WINDOW) {
        failures++; report(ImmersiveFailure.wrap(phase, field, error))
    }
    fun release(activity: Activity) { windows.remove(activity.window)?.let { row ->
        val before = enabled; enabled = false
        try { apply(activity.window, row) } finally { enabled = before }
    } }
    private fun releaseBackground(view: View) {
        val row = backgrounds.remove(view) ?: return
        val before = enabled; enabled = false
        try { applyBackground(view, row) } finally { enabled = before }
        row.listener?.let(view::removeOnAttachStateChangeListener)
        row.baseline = null
    }
    override fun close() {
        if (closed) return
        configure(false)
        backgrounds.keys.toList().forEach(::releaseBackground)
        windows.clear(); closed = true
    }
}
