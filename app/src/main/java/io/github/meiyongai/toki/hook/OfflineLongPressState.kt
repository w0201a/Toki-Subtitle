package io.github.meiyongai.toki.subtitle.hook

/** No touch objects or Android windows retained here; the native click path runs until a hold is claimed. */
internal class OfflineLongPressState {
    private var x = 0f
    private var y = 0f
    private var pending = false
    var claimed = false
        private set
    var token = 0L
        private set
    fun begin(x: Float, y: Float): Long {
        cancel(); this.x = x; this.y = y; pending = true
        return token
    }
    fun move(x: Float, y: Float, pointers: Int, slop: Float) {
        if (pointers != 1 || !x.isFinite() || !y.isFinite() || !slop.isFinite() ||
            (x - this.x) * (x - this.x) + (y - this.y) * (y - this.y) > slop * slop) {
            if (!claimed) cancel()
        }
    }
    fun claim(expected: Long): Boolean {
        if (!pending || expected != token) return false
        pending = false; claimed = true
        return true
    }
    fun cancel() { token++; pending = false; claimed = false }
}
