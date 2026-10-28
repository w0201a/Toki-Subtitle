package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class SmartVideoLayoutTest {
    @Test fun fillUsesMeasuredCellInsteadOfNarrowHostViewport() {
        val contract = SmartVideoLayout(Feed::class.java, Result::class.java, Multi::class.java, fill = true)
        val op = Multi(0, 0, 0f, 0f, 1920f, 1000f, Scale.FIT, Align.WIDTH, 0f,
            Params(1080, 1920, 1000, 1920), null)
        val original = Result(1000, 1778, 20f, 0f, op)
        val adjusted = contract.replace(original, 1080, 2400) as Result
        assertEquals(2400, adjusted.height)
        assertTrue(adjusted.width >= 1080)
        assertEquals(1080f, (adjusted.resultOperator as Multi).adjustContainerWidth)
        assertEquals(1000, original.width)
    }

    @Test fun fillSupportsAlternativePolicyAndDoesNotMutateIt() {
        val contract = SmartVideoLayout(Feed::class.java, Result::class.java, Multi::class.java, fill = true)
        val policy = object : Operator {}
        val original = Result(1000, 2000, 12f, 20f, policy)
        assertSame(original, contract.replace(original)) // Wait for a measured cell.
        val adjusted = contract.replace(original, 1080, 2400, 120) as Result
        assertEquals(2880, adjusted.height)
        assertEquals(1440, adjusted.width)
        assertSame(policy, adjusted.resultOperator)
        assertEquals(0f, adjusted.translateX)
        assertEquals(12f, original.translateX)
    }
    enum class Align { WIDTH, HEIGHT, MATCH_PARENT }
    enum class Scale { FIT, CROP }
    interface Operator
    data class Params(val videoWidth: Int, val videoHeight: Int, val containerWidth: Int, val containerHeight: Int)
    data class Multi(val topType: Int, val bottomType: Int, val topHeight: Float, val bottomHeight: Float,
        val adjustContainerHeight: Float, val adjustContainerWidth: Float, val adaptionScaleType: Scale,
        val alignType: Align, val areaDiff: Float, val videoAdaptionParams: Params, val ocrEffective: Boolean?) : Operator
    data class Result(val width: Int, val height: Int, val translateX: Float?, val translateY: Float?, val resultOperator: Operator?)
    open class Base {
        /** 宿主提交契约测试替身。@param result 布局结果。@param reason 原因。@return Unit。Callers: Feed。 */
        open fun submit(result: Result?, reason: String) = Unit
    }
    class Feed : Base() {
        /** 宿主覆盖方法测试替身。@param result 布局结果。@param reason 原因。@return Unit。Callers: 反射契约验证。 */
        override fun submit(result: Result?, reason: String) = Unit
    }
    /** 无参数。验证 MATCH_PARENT 分支转换、参数保留及原对象不被修改。返回 Unit。Callers: JUnit。 */
    @Test fun replacesPortraitGeometryWithoutMutatingCachedResult() {
        val contract = SmartVideoLayout(Feed::class.java, Result::class.java, Multi::class.java)
        val params = Params(1080, 1920, 1080, 2356)
        val op = Multi(1, 2, 100f, 200f, 2056f, 1080f, Scale.FIT, Align.MATCH_PARENT, 0.3f, params, true)
        val original = Result(1080, 1920, 10f, 20f, op)
        val changed = contract.replace(original) as Result
        assertEquals(2356, changed.height)
        assertTrue(changed.width > 1080)
        assertEquals(0f, changed.translateY)
        val adjusted = changed.resultOperator as Multi
        assertEquals(Align.HEIGHT, adjusted.alignType)
        assertEquals(Scale.CROP, adjusted.adaptionScaleType)
        assertSame(params, adjusted.videoAdaptionParams)
        assertEquals(true, adjusted.ocrEffective)
        assertEquals(20f, original.translateY)
        assertEquals(Align.MATCH_PARENT, op.alignType)
    }
    /** 无参数。验证横屏完整显示、未测量和不支持的策略保留。返回 Unit。Callers: JUnit。 */
    @Test fun landscapeIsContainedAndOtherStrategiesStayUntouched() {
        val contract = SmartVideoLayout(Feed::class.java, Result::class.java, Multi::class.java)
        val op = Multi(0, 0, 0f, 0f, 2356f, 1080f, Scale.CROP, Align.MATCH_PARENT, 0f,
            Params(1920, 1080, 1080, 2356), null)
        val changed = contract.replace(Result(1920, 1080, null, null, op)) as Result
        assertEquals(1080, changed.width)
        assertEquals(608, changed.height)
        assertEquals(Scale.FIT, (changed.resultOperator as Multi).adaptionScaleType)
        val special = Result(1, 1, null, null, object : Operator {})
        assertSame(special, contract.replace(special))
        val unmeasured = Result(1, 1, null, null, op.copy(videoAdaptionParams = Params(0, 0, 1080, 2356)))
        assertSame(unmeasured, contract.replace(unmeasured))
        assertNull(contract.replace(null))
    }
}
