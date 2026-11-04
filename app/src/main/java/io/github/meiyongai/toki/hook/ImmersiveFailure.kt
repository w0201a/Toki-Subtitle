package io.github.meiyongai.toki.subtitle.hook

/** Typed stage/field metadata only. Native exception messages, addresses and user text never enter this summary. */
internal class ImmersiveFailure(val phase: Phase, val field: Field, cause: Exception) : Exception(null, cause) {
    enum class Phase(val title: String) {
        CONFIGURATION("读取当前配置"), CAPTURE("保存原值"), APPLY("应用窗口"), RESTORE("恢复窗口"), BACKGROUND_APPLY("隐藏背景"),
        BACKGROUND_RESTORE("恢复背景"), WINDOW_REFRESH("刷新窗口"), PANEL_REFRESH("重算屏幕"),
        CELL_REFRESH("重算卡片"), CACHE_RESTORE("恢复缓存"), PUBLISH("通知布局")
    }
    enum class Field(val title: String) {
        CONFIGURATION("配置快照"), STATUS("状态栏颜色"), NAVIGATION("导航栏颜色"), STATUS_CONTRAST("状态栏对比度"),
        NAVIGATION_CONTRAST("导航栏对比度"), CUTOUT("刘海模式"), FITS("系统栏适配"), FLAGS("布局标志"),
        BACKGROUND("底栏背景"), SCREEN("屏幕结果"), CACHE("布局缓存"), WINDOW("窗口"), CELL("当前卡片")
    }
    fun summary(): String {
        var root: Throwable = cause ?: this
        val visited = java.util.IdentityHashMap<Throwable, Unit>()
        repeat(12) {
            if (visited.put(root, Unit) != null) return@repeat
            val next = root.cause
            if (next != null && !visited.containsKey(next)) root = next
        }
        val kind = root.javaClass.simpleName.ifBlank { "Exception" }.take(120)
        return "阶段=${phase.title} 字段=${field.title} 根因类型=$kind"
    }
    companion object {
        fun wrap(phase: Phase, field: Field, error: Exception): ImmersiveFailure =
            error as? ImmersiveFailure ?: ImmersiveFailure(phase, field, error)
        fun describe(error: Exception): String = (error as? ImmersiveFailure)?.summary()
            ?: wrap(Phase.WINDOW_REFRESH, Field.WINDOW, error).summary()
    }
}
