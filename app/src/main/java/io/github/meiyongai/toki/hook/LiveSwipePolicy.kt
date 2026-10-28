package io.github.meiyongai.toki.subtitle.hook

import kotlin.math.abs

/** 判断一次触摸位移是否属于应以纵向为主的手势；不依赖宿主混淆类名。 */
internal object LiveSwipePolicy {
    /**
     * 仅当纵向位移达到系统滑动阈值且严格大于横向位移时拦截。
     * @param dx 当前相对按下点的横向位移。
     * @param dy 当前相对按下点的纵向位移。
     * @param touchSlop 系统 scaledTouchSlop，非正数视为尚未测量。
     * @return 是否应阻止直播容器继续处理该手势。
     * Callers: LiveSwipeLockHook.shouldBlock；LiveSwipePolicyTest。
     */
    fun blocks(dx: Float, dy: Float, touchSlop: Float): Boolean {
        if (!touchSlop.isFinite() || touchSlop <= 0f) return false
        return abs(dy) >= touchSlop && abs(dy) > abs(dx)
    }
}
