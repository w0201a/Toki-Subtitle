package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest

/** Read only the host's account-specific persisted snapshot; never invoke its network/cache-population jobs. */
internal class HostOfflineComments(private val loader: ClassLoader) {
    private val reflected = lazy { Contract(loader) }
    private val media = lazy { OfflineCommentMediaReader(loader) }

    fun accountMatches(token: String): Boolean = try {
        reflected.isInitialized() && token == accountToken(reflected.value.account())
    } catch (_: Exception) { false } catch (_: LinkageError) { false }

    fun read(context: Context, video: String, active: () -> Boolean): OfflineCommentData.Snapshot {
        fun requireCurrent() {
            if (!active() || Thread.currentThread().isInterrupted)
                throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CANCELLED)
        }
        requireCurrent()
        if (!OfflineCommentCodeGate.verify(context)) throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.UNSUPPORTED)
        require(video.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val contract = reflected.value
        val account = contract.account()
        OfflineCommentArchive.window(context, accountToken(account), video)?.let { return it.snapshot }
        val repo = contract.repo.invoke(null, "offline_comment_" + account)
        val raw = contract.getString.invoke(repo, "snap_" + video, null) as? String
            ?: throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.MISSING)
        requireCurrent()
        val native = try {
            OfflineCommentJsonBounds.check(raw)
            contract.parse.invoke(contract.gson.get(null), raw, contract.snapshot)
                ?: throw IllegalArgumentException("Empty native snapshot")
        } catch (_: Exception) { throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT) }
        if (contract.snapshotId.get(native) != video) throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT)
        val response = contract.response.get(native) ?: throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT)
        return copyResponse(context, video, accountToken(account), response, contract.cachedTime.getLong(native), active = active)
    }

    fun account(context: Context): String {
        if (!OfflineCommentCodeGate.verify(context)) throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.UNSUPPORTED)
        return accountToken(reflected.value.account())
    }

    fun serializeModel(model: Any): String = reflected.value.serialize.invoke(reflected.value.gson.get(null), model) as String
    fun parseModel(text: String): Any {
        require(text.length<=4*1024*1024)
        return checkNotNull(reflected.value.parse.invoke(reflected.value.gson.get(null), text,
            Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme",false,loader)))
    }

    fun copyResponse(context: Context, video: String, account: String, response: Any, capturedAt: Long,
        root: String? = null, active: () -> Boolean): OfflineCommentData.Snapshot {
        val contract = reflected.value
        fun requireCurrent() {
            if (!active() || Thread.currentThread().isInterrupted)
                throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CANCELLED)
        }
        requireCurrent()
        if (!OfflineCommentCodeGate.verify(context) || !accountMatches(account))
            throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.ACCOUNT_CHANGED)
        if (!contract.list.isInstance(response) || contract.status.getInt(response) != 0 || contract.error.getInt(response) != 0) throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT)
        val responseRoot = contract.root.invoke(response) as? String
        if (root != null && !responseRoot.isNullOrBlank() && responseRoot != root)
            throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT)
        val sourceRows = contract.items.invoke(response) as? List<*>
            ?: throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT)
        val result = mutableListOf<OfflineCommentData.Row>()
        val seen = mutableSetOf<String>()
        var truncated = false
        var visited = 0
        fun append(items: List<*>, depth: Int, parentId: String = "") {
            for (item in items) {
                requireCurrent()
                if (++visited > 1000 || result.size >= OfflineCommentData.MAX_ROWS) { truncated = true; return }
                if (item == null || !contract.comment.isInstance(item)) { truncated = true; continue }
                val id = contract.id.invoke(item) as? String
                if (id.isNullOrBlank() || id.length > 128 || !seen.add(id)) { truncated = true; continue }
                val ownerVideo = contract.video.invoke(item) as? String
                if (!ownerVideo.isNullOrBlank() && ownerVideo != video) { truncated = true; continue }
                val visible = contract.text.invoke(item) as? String
                val original = OfflineCommentData.original(contract.original.invoke(item) as? String, visible)
                val shortened = original.length > OfflineCommentData.MAX_TEXT
                val source = OfflineCommentData.bounded(original, OfflineCommentData.MAX_TEXT)
                val translation = if (shortened) null else OfflineCommentData.translated(source,
                    contract.translated.invoke(item) as? String, visible, contract.isTranslated.invoke(item) == true)
                val user = contract.user.invoke(item)
                result += OfflineCommentData.Row(id,
                    OfflineCommentData.bounded(user?.let { contract.nickname.invoke(it) as? String }, 256),
                    OfflineCommentData.bounded(user?.let { contract.handle.invoke(it) as? String }, 128),
                    OfflineCommentData.bounded(user?.let { contract.uid.invoke(it) as? String }, 128),
                    source, translation, OfflineCommentData.bounded(contract.language.invoke(item) as? String, 64),
                    depth, (contract.replyCount.invoke(item) as Int).toLong().coerceAtLeast(0), shortened, parentId, media.value.read(item), true)
                val replies = contract.replies.invoke(item) as? List<*>
                if ((contract.replyCount.invoke(item) as Int).toLong() > replies.orEmpty().size) truncated = true
                if (!replies.isNullOrEmpty()) {
                    if (depth < 3) append(replies, depth + 1, id) else truncated = true
                }
            }
        }
        append(sourceRows, 0)
        requireCurrent()
        if (!accountMatches(account)) throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.ACCOUNT_CHANGED)
        val total = (contract.total.invoke(response) as Long).coerceAtLeast(0)
        return OfflineCommentData.Snapshot(account, video, capturedAt.coerceAtLeast(0),
            result.toList(), total, contract.hasMore.invoke(response) == true, truncated || total > sourceRows.size,
            nextCursor = (contract.cursor.invoke(response) as Long).takeIf { it >= 0 })
    }

    private class Contract(private val loader: ClassLoader) {
        private fun type(name: String) = Class.forName(name, false, loader)
        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg arguments: Class<*>): Method =
            owner.getMethod(name, *arguments).apply { check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true }
        private val store = type("X.0XJH")
        private val accountId = store.getDeclaredMethod("LJIIJ").apply {
            check(returnType == String::class.java && Modifier.isStatic(modifiers)); isAccessible = true
        }
        fun account(): String = (accountId.invoke(null) as? String)?.takeIf {
            it.length <= 128 && it.matches(Regex("[0-9]+|guest"))
        } ?: throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.ACCOUNT_CHANGED)
        private val keva = type("com.bytedance.keva.Keva")
        val repo = keva.getDeclaredMethod("getRepo", String::class.java).apply {
            check(returnType == keva && Modifier.isStatic(modifiers)); isAccessible = true
        }
        val getString = method(keva, "getString", String::class.java, String::class.java, String::class.java)
        private val gsonType = type("com.google.gson.Gson")
        val gson = store.getDeclaredField("LJ").apply { check(type == gsonType && Modifier.isStatic(modifiers)); isAccessible = true }
        val parse = method(gsonType, "LJI", Any::class.java, String::class.java, Class::class.java)
        val serialize = method(gsonType,"LJIILL",String::class.java,Any::class.java)
        val snapshot = type("X.0XJO")
        val list = type("com.ss.android.ugc.aweme.comment.model.CommentItemList")
        val snapshotId = snapshot.getDeclaredField("LIZ").apply { check(type == String::class.java); isAccessible = true }
        val response = snapshot.getDeclaredField("LIZIZ").apply { check(type == list); isAccessible = true }
        val cachedTime = snapshot.getDeclaredField("LIZJ").apply { check(type == java.lang.Long.TYPE); isAccessible = true }
        val status = list.getField("status_code").apply { check(type == java.lang.Integer.TYPE); isAccessible = true }
        val error = list.getField("error_code").apply { check(type == java.lang.Integer.TYPE); isAccessible = true }
        val root = method(list, "getRootCid", String::class.java)
        val items = method(list, "getItems", List::class.java)
        val total = method(list, "getTotal", java.lang.Long.TYPE)
        val cursor = method(list, "getCursor", java.lang.Long.TYPE)
        val hasMore = method(list, "isHasMore", java.lang.Boolean.TYPE)
        val comment = type("com.ss.android.ugc.aweme.comment.model.Comment")
        val id = method(comment, "getCid", String::class.java)
        val video = method(comment, "getAwemeId", String::class.java)
        val text = method(comment, "getText", String::class.java)
        val original = method(comment, "getOriginalText", String::class.java)
        val translated = method(comment, "getTranslatedText", String::class.java)
        val language = method(comment, "getTranslatedTextLanguage", String::class.java)
        val isTranslated = method(comment, "isTranslated", java.lang.Boolean.TYPE)
        val replies = method(comment, "getReplyComments", List::class.java)
        val replyCount = method(comment, "getReplyCount", java.lang.Integer.TYPE)
        private val profile = type("com.ss.android.ugc.aweme.profile.model.User")
        val user = method(comment, "getUser", profile)
        val nickname = method(profile, "getNickname", String::class.java)
        val handle = method(profile, "getUniqueId", String::class.java)
        val uid = method(profile, "getUid", String::class.java)
    }

    companion object {
        internal fun accountToken(account: String): String = MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
