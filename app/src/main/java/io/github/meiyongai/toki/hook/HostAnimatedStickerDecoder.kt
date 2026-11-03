package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.graphics.Bitmap
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Inspected host byte-array decoders only. No URL decoder, cache fetch or native library installation. */
internal class HostAnimatedStickerDecoder private constructor(loader: ClassLoader, source: AnimatedStickerData.Sequence, bytes: ByteArray) : AutoCloseable {
    private val imageClass = loader.loadClass(if (source.source == "gif") "com.facebook.animated.gif.GifImage" else "com.facebook.animated.webp.WebPImage")
    private val frameClass = loader.loadClass(if (source.source == "gif") "com.facebook.animated.gif.GifFrame" else "com.facebook.animated.webp.WebPFrame")
    private fun method(type: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method =
        type.getMethod(name, *parameters).apply { check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true }
    private val disposeImage = method(imageClass, "dispose", Void.TYPE)
    private val frameMethod = imageClass.declaredMethods.single { it.name == "getFrame" &&
        it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType!!)) && it.returnType == frameClass && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
    private val disposeFrame = method(frameClass, "dispose", Void.TYPE)
    private val width = method(frameClass, "getWidth", Int::class.javaPrimitiveType!!)
    private val height = method(frameClass, "getHeight", Int::class.javaPrimitiveType!!)
    private val x = method(frameClass, "getXOffset", Int::class.javaPrimitiveType!!)
    private val y = method(frameClass, "getYOffset", Int::class.javaPrimitiveType!!)
    private val renderFrame = method(frameClass, "renderFrame", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Bitmap::class.java)
    private val handle: Any
    private var closed = false
    init {
        val create = imageClass.getDeclaredMethod(if (source.source == "gif") "createFromByteArray" else "create", ByteArray::class.java).apply {
            check(returnType == imageClass && Modifier.isStatic(modifiers)); isAccessible = true
        }
        handle = checkNotNull(create.invoke(null, bytes))
        try {
            fun integer(name: String): Int = method(imageClass, name, Int::class.javaPrimitiveType!!).invoke(handle) as Int
            require(integer("getWidth") == source.width && integer("getHeight") == source.height && integer("getFrameCount") == source.frames.size)
            require(integer("getSizeInBytes") in 1..128 * 1024 * 1024) { "Native animation memory budget exceeded" }
        } catch (error: Throwable) {
            try { disposeImage.invoke(handle) } catch (_: Exception) { }
            throw error
        }
    }
    fun render(index: Int, expected: AnimatedStickerData.Frame, bitmap: Bitmap) {
        check(!closed)
        val frame = checkNotNull(frameMethod.invoke(handle, index))
        try {
            require(width.invoke(frame) == expected.width && height.invoke(frame) == expected.height &&
                x.invoke(frame) == expected.x && y.invoke(frame) == expected.y) { "Native frame geometry differs from container" }
            renderFrame.invoke(frame, bitmap.width, bitmap.height, bitmap)
        } finally { disposeFrame.invoke(frame) }
    }
    override fun close() { if (!closed) { closed = true; disposeImage.invoke(handle) } }
    companion object {
        private val hashes = mapOf(
            "classes10.dex" to "02c0273c05a26a73670bab39e253b1dd8aa049fa07f8aa78ebbdcda5417b7655",
            "classes13.dex" to "3cf02a1bfae4dc0a5b3f9ce01388be69e56aff68e080ae3f53b0574010cc86d8")
        @Volatile private var supported: Boolean? = null
        @Synchronized private fun verify(context: Context): Boolean {
            supported?.let { return it }
            val matched = mutableSetOf<String>()
            try {
                val info = context.applicationInfo
                (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path -> ZipFile(path).use { zip ->
                    hashes.forEach entries@ { (name, expected) ->
                        val entry = zip.getEntry(name) ?: return@entries
                        require(entry.size in 1..32L * 1024 * 1024)
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) return false
                                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                            }
                        }
                        if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) matched += name
                    }
                } }
            } catch (_: Exception) { matched.clear() }
            return (matched == hashes.keys).also { supported = it }
        }
        fun requireInspectedCode(context: Context) { check(verify(context)) { "Animation decoder host code is not inspected" } }
        fun open(context: Context, loader: ClassLoader, source: AnimatedStickerData.Sequence, bytes: ByteArray): HostAnimatedStickerDecoder {
            requireInspectedCode(context)
            return try { HostAnimatedStickerDecoder(loader, source, bytes) }
            catch (error: LinkageError) { throw IllegalStateException("Host animation codec unavailable", error) }
        }
    }
}
