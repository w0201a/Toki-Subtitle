package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Durable per-account/video companion pages in no-backup storage, not an evictable LRU/cacheDir. */
internal object OfflineCommentArchive {
    data class Window(val snapshot: OfflineCommentData.Snapshot, val offset: Int, val next: Int?)
    private fun base(context:Context)=File(context.noBackupFilesDir,"toki-offline-comments-v2")
    private fun folder(context: Context, account: String, video: String): File = File(base(context),CommentSnapshotStore.key(account,video))
    /** Invoked only by a code-verified native manual-clear-all event, never by age/LRU or invalidation. */
    @Synchronized fun clearAccountAfterManualClear(context:Context,account:String) {
        require(account.matches(Regex("[a-f0-9]{64}")))
        val base=base(context).canonicalFile
        for(dir in base.listFiles().orEmpty()) {
            if(!dir.isDirectory||dir.canonicalFile.parentFile!=base||!dir.name.matches(Regex("[a-f0-9]{64}")))continue
            val m=try{index(dir)}catch(_:Exception){continue}
            if(m.optString("account")!=account)continue
            for(file in dir.listFiles().orEmpty()) {
                // Module-owned flat files only; no recursion or host-data paths.
                if(file.canonicalFile.parentFile!=dir.canonicalFile||file.isDirectory)continue
                if(file.name.matches(Regex("[a-f0-9]{64}\\.bin(?:\\.bak|\\.new)?|(?:index|model)\\.json(?:\\.bak|\\.new)?")))
                    check(file.delete()||!file.exists())
            }
            val media = File(dir, "media")
            if (media.isDirectory && media.canonicalFile.parentFile == dir.canonicalFile) {
                for (file in media.listFiles().orEmpty()) {
                    if (!file.isDirectory && file.canonicalFile.parentFile == media.canonicalFile &&
                        file.name.matches(Regex("[a-f0-9]{64}\\.(?:bin|json)(?:\\.new)?"))) check(file.delete() || !file.exists())
                }
                if (media.listFiles().isNullOrEmpty()) check(media.delete() || !media.exists())
            }
            if(dir.listFiles().isNullOrEmpty())check(dir.delete()||!dir.exists())
        }
    }
    private fun pageKey(account:String,cursor:Long,root:String):String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((account+":"+cursor+":"+root).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun index(dir: File): JSONObject {
        val file = AtomicFile(File(dir, "index.json"))
        if (!file.baseFile.exists() && !File(dir,"index.json.bak").exists()) return JSONObject().put("version",1).put("pages",JSONObject())
        return JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).also { require(it.getInt("version")==1) }
    }
    private fun write(file: File, bytes: ByteArray) {
        // Checked replace, unlike AtomicFile.finishWrite which can log a failed rename without throwing.
        val pending=File(file.parentFile,file.name+".new")
        java.io.FileOutputStream(pending).use{output->output.write(bytes);output.fd.sync()}
        java.nio.file.Files.move(pending.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        check(file.readBytes().contentEquals(bytes)){"Archive readback mismatch"}
    }
    @Synchronized fun saveModel(context:Context,account:String,video:String,text:String) {
        require(text.toByteArray(Charsets.UTF_8).size<=4*1024*1024)
        val dir=folder(context,account,video);check(dir.isDirectory||dir.mkdirs())
        val m=index(dir);m.put("account",account).put("video",video)
        write(File(dir,"index.json"),m.toString().toByteArray(Charsets.UTF_8))
        write(File(dir,"model.json"),text.toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun model(context:Context,account:String,video:String):String {
        return AtomicFile(File(folder(context,account,video),"model.json")).openRead().use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536);var total=0
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=4*1024*1024);out.write(buffer,0,n)}
            out.toString("UTF-8")
        }
    }
    @Synchronized fun updateTranslation(context:Context,snapshot:OfflineCommentData.Snapshot,row:OfflineCommentData.Row,target:String,text:String):Boolean {
        val dir=folder(context,snapshot.account,snapshot.video);val m=index(dir);val pages=m.getJSONObject("pages")
        var saved=false
        for(name in pages.keys()) {
            val file=File(dir,"$name.bin");val old=CommentSnapshotCodec.decode(AtomicFile(file).openRead().use{it.readBytes()})
            if(old.rows.any{it.id==row.id&&it.source==row.source}) {
                val new=old.copy(rows=old.rows.map{if(it.id==row.id&&it.source==row.source)it.copy(translation=text,language=target) else it})
                write(file,CommentSnapshotCodec.encode(new));saved=true
            }
        }
        if(!saved)save(context,snapshot.copy(rows=snapshot.rows.map{if(it.id==row.id&&it.source==row.source)it.copy(translation=text,language=target) else it}),0)
        return true
    }
    @Synchronized fun save(context: Context, page: OfflineCommentData.Snapshot, cursor: Long, root: String = "", active: ()->Boolean = {true}) {
        require(cursor>=0 && root.length<=128)
        val bytes=CommentSnapshotCodec.encode(page);if(!active())throw IllegalStateException("Cancelled archive write")
        val dir=folder(context,page.account,page.video);check(dir.isDirectory||dir.mkdirs())
        val manifest=index(dir);val pages=manifest.getJSONObject("pages")
        val name=pageKey(page.account,cursor,root)
        // Full source+translation are one atomic page; failures never evict an older video.
        write(File(dir,"$name.bin"),bytes)
        pages.put(name,JSONObject().put("cursor",cursor).put("root",root).put("rows",page.rows.size))
        if(root.isEmpty())manifest.put("total",maxOf(manifest.optLong("total"),page.total))
        manifest.put("account",page.account).put("video",page.video).put("updated",System.currentTimeMillis())
        write(File(dir,"index.json"),manifest.toString().toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun page(context: Context, account: String, video: String, cursor: Long, root: String = ""): OfflineCommentData.Snapshot? {
        val dir=folder(context,account,video);val manifest=index(dir)
        val name=pageKey(account,cursor,root)
        if(!manifest.getJSONObject("pages").has(name))return null
        return CommentSnapshotCodec.decode(AtomicFile(File(dir,"$name.bin")).openRead().use{it.readBytes()})
            .also{require(it.account==account&&it.video==video)}
    }
    @Synchronized fun coverage(context:Context,account:String,video:String):Boolean {
        val dir=folder(context,account,video);val m=index(dir);val pages=m.getJSONObject("pages")
        val top=HashSet<String>();val expected=HashMap<String,Long>();val replies=HashMap<String,MutableSet<String>>()
        for(name in pages.keys()) {
            val page=CommentSnapshotCodec.decode(AtomicFile(File(dir,"$name.bin")).openRead().use{it.readBytes()})
            require(page.account==account&&page.video==video)
            for(row in page.rows)if(row.parentId.isBlank()&&row.depth==0){top.add(row.id);expected[row.id]=maxOf(expected[row.id]?:0L,row.replyCount)}
            else replies.getOrPut(row.parentId){HashSet()}.add(row.id)
        }
        return OfflineCommentDownloadProgress.complete(m.optLong("total"),top.size.toLong(),expected,replies.mapValues{it.value.size.toLong()})
    }
    @Synchronized fun window(context: Context, account: String, video: String, offset: Int=0, limit: Int=2500, windowSize: Int=OfflineCommentData.MAX_ROWS): Window? {
        require(offset>=0 && limit in 1..2500 && windowSize in 1..2500);val dir=folder(context,account,video);val manifest=index(dir)
        val pages=manifest.getJSONObject("pages");if(pages.length()==0)return null
        val groups=pages.keys().asSequence().toList().groupBy{pages.getJSONObject(it).getString("root")}
            .mapValues{(_,names)->names.sortedBy{pages.getJSONObject(it).getLong("cursor")}}
        val rows=ArrayList<OfflineCommentData.Row>();val seen=HashSet<String>();var unique=0;var first:OfflineCommentData.Snapshot?=null
        fun read(name:String):OfflineCommentData.Snapshot=CommentSnapshotCodec.decode(AtomicFile(File(dir,"$name.bin")).openRead().use{it.readBytes()})
            .also{require(it.account==account&&it.video==video)}
        fun append(row:OfflineCommentData.Row){if(unique < limit && seen.add(row.id)){if(unique++>=offset&&rows.size<windowSize)rows.add(row)}}
        outer@for(name in groups[""].orEmpty()){
            val page=read(name);if(first==null)first=page
            for(row in page.rows){
                append(row)
                if(row.depth==0&&row.parentId.isBlank())for(replyPage in groups[row.id].orEmpty())for(reply in read(replyPage).rows){
                    append(reply);if(unique>=limit || unique>offset+rows.size)break@outer
                }
                if(unique>=limit || unique>offset+rows.size)break@outer
            }
        }
        val base=first?:return null
        return Window(base.copy(rows=rows,total=manifest.optLong("total",base.total),capturedAt=manifest.optLong("updated"),
            hasMore=false,nextCursor=null,truncated=manifest.optString("state")!="complete" || manifest.optBoolean("limited")),offset,
            if(unique>offset+rows.size && offset+rows.size<limit)offset+rows.size else null)
    }
    /** UI-only aggregate. Individual stored/server pages retain the bounded codec row limit. */
    @Synchronized fun display(context: Context, account: String, video: String, limit: Int): Window? =
        window(context, account, video, 0, limit, limit)
    data class TranslationWork(val page: OfflineCommentData.Snapshot, val row: OfflineCommentData.Row)
    @Synchronized fun selectedRows(context: Context, account: String, video: String, limit: Int = 2500): List<OfflineCommentData.Row> {
        val result = ArrayList<OfflineCommentData.Row>(); var offset = 0
        while (result.size < limit) {
            val value = window(context, account, video, offset, limit) ?: break
            result.addAll(value.snapshot.rows)
            offset = value.next ?: break
        }
        return result.take(limit)
    }
    data class Page(val cursor: Long, val root: String)
    @Synchronized fun metadataPage(context: Context, account: String, video: String, limit: Int, refreshMissing: Boolean = false): Page? {
        val missing = selectedRows(context, account, video, limit).filter { !it.mediaKnown || refreshMissing && it.media.any { asset -> OfflineCommentMediaStore.file(context, account, video, asset) == null } }.mapTo(hashSetOf()) { it.id }
        if (missing.isEmpty()) return null
        val dir = folder(context, account, video); val pages = index(dir).getJSONObject("pages")
        for (name in pages.keys()) {
            val page = CommentSnapshotCodec.decode(AtomicFile(File(dir, "$name.bin")).openRead().use { it.readBytes() })
            if (page.rows.any { it.id in missing }) return pages.getJSONObject(name).let { Page(it.getLong("cursor"), it.getString("root")) }
        }
        return null
    }
    @Synchronized fun nextTranslation(context: Context, account: String, video: String, target: String, limit: Int = 2500): TranslationWork? {
        val rows = selectedRows(context, account, video, limit)
        val row = rows.firstOrNull { it.source.isNotBlank() && (it.sourceTruncated || it.translation.isNullOrBlank() ||
            CommentTranslationKey.normalizedTarget(it.language) != target) } ?: return null
        val first = window(context, account, video, 0, limit) ?: return null
        return TranslationWork(first.snapshot.copy(rows = listOf(row)), row)
    }
    @Synchronized fun translationsReady(context: Context, account: String, video: String, target: String, limit: Int = 2500): Boolean {
        if (page(context, account, video, 0L) == null) return false
        return nextTranslation(context, account, video, target, limit) == null
    }
    @Synchronized fun bounded(context: Context, account: String, video: String, limit: Int, limited: Boolean) {
        val dir = folder(context, account, video); val manifest = index(dir)
        manifest.put("cacheLimit", limit).put("limited", limited)
        write(File(dir, "index.json"), manifest.toString().toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun nextMedia(context: Context, account: String, video: String, limit: Int): OfflineCommentData.Media? =
        selectedRows(context, account, video, limit).asSequence().flatMap { it.media.asSequence() }
            .firstOrNull { OfflineCommentMediaStore.file(context, account, video, it) == null }
    @Synchronized fun mediaReady(context: Context, account: String, video: String, limit: Int): Boolean =
        page(context, account, video, 0L) != null && selectedRows(context, account, video, limit).all { row ->
            row.mediaKnown && row.media.all { OfflineCommentMediaStore.file(context, account, video, it) != null }
        }
    /** Background completion may only update rows that still exist with the same source. It never creates a guessed page. */
    @Synchronized fun commitTranslation(context: Context, account: String, video: String, row: OfflineCommentData.Row,
        target: String, translated: String, active: () -> Boolean): Boolean {
        require(!row.sourceTruncated && translated.isNotBlank() && translated.length <= 80000)
        if (!active()) return false
        val dir = folder(context, account, video); val pages = index(dir).getJSONObject("pages"); var updated = false
        for (name in pages.keys()) {
            val file = File(dir, "$name.bin")
            val old = CommentSnapshotCodec.decode(AtomicFile(file).openRead().use { it.readBytes() })
            require(old.account == account && old.video == video)
            if (old.rows.any { it.id == row.id && it.source == row.source }) {
                if (!active()) return false
                write(file, CommentSnapshotCodec.encode(old.copy(rows = old.rows.map {
                    if (it.id == row.id && it.source == row.source) it.copy(translation = translated, language = target) else it
                })))
                updated = true
            }
        }
        return updated
    }
    @Synchronized fun status(context: Context, account:String, video:String, state:String, detail:String="") {
        require(state in setOf("pending","fetching","translating","partial","complete","failed"))
        val dir=folder(context,account,video);check(dir.isDirectory||dir.mkdirs());val m=index(dir)
        m.put("account",account).put("video",video).put("state",state).put("detail",detail.take(160)).put("updated",System.currentTimeMillis())
        write(File(dir,"index.json"),m.toString().toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun state(context:Context,account:String,video:String):String= index(folder(context,account,video)).optString("state","missing")
}
