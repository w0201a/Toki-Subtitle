package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Durable facts, item work and lane turns share one transaction boundary. No network or UI in this class. */
internal class CommentRepository(private val context: Context, file: File = File(context.noBackupFilesDir,
    "toki-offline-comments-v3/comments.sqlite")) : AutoCloseable {
    enum class Lane { SOURCE, TRANSLATION, MEDIA }
    data class Work(val account: String, val video: String, val lane: Lane, val item: String,
        val version: String, val target: String, val lease: String, val epoch: Long, val cursor: Long,
        val model: String, val row: OfflineCommentData.Row? = null, val asset: OfflineCommentData.Media? = null, val manualAuthorized:Boolean=false)
    data class ItemState(val status: String, val detail: String, val retryAt: Long)
    data class VideoState(val snapshot: OfflineCommentData.Snapshot, val storedRoots: Int, val storedReplies: Int,
        val translated: Int, val translatable: Int, val mediaReady: Int, val mediaTotal: Int,
        val phase: String, val reason: String, val paused: Boolean, val networkAuthorized: Boolean,
        val translationStates: Map<String, ItemState>, val mediaStates: Map<String, ItemState>, val migrationErrors: Int)
    sealed class Command {
        data object Prepare : Command()
        data object Pause : Command()
        data object Resume : Command()
        data object Retry : Command()
        data class Translate(val comment: String) : Command()
    }
    companion object {
        private var shared: CommentRepository? = null
        @Synchronized fun get(context: Context): CommentRepository = shared ?: CommentRepository(context.applicationContext).also { shared = it }
        fun hash(text: String): String = DigestHex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        fun needsTranslation(row: OfflineCommentData.Row): Boolean = row.source.codePoints().anyMatch { Character.isLetterOrDigit(it) }
        fun rowJson(r: OfflineCommentData.Row): String = JSONObject().put("id",r.id).put("author",r.author).put("handle",r.handle)
            .put("authorId",r.authorId).put("source",r.source).put("depth",r.depth).put("replies",r.replyCount)
            .put("parent",r.parentId).put("truncated",r.sourceTruncated).put("known",r.mediaKnown)
            .put("media",JSONArray(r.media.map { JSONObject(assetJson(it)) })).toString()
        fun assetJson(a: OfflineCommentData.Media): String = JSONObject().put("id",a.id).put("kind",a.kind.name)
            .put("animated",a.animated).put("urls",JSONArray(a.urls)).toString()
        fun asset(text: String): OfflineCommentData.Media = JSONObject(text).let { j ->
            OfflineCommentData.Media(j.getString("id"),OfflineCommentData.MediaKind.valueOf(j.getString("kind")),
                j.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } },j.optBoolean("animated")) }
        fun row(text: String): OfflineCommentData.Row = JSONObject(text).let { j -> OfflineCommentData.Row(j.getString("id"),
            j.optString("author"),j.optString("handle"),j.optString("authorId"),j.optString("source"),null,"",j.optInt("depth"),
            j.optLong("replies"),j.optBoolean("truncated"),j.optString("parent"),j.getJSONArray("media").let { a ->
                (0 until a.length()).map { asset(a.getJSONObject(it).toString()) } },j.optBoolean("known")) }
    }
    private val db: SQLiteDatabase
    private val liveLeases=java.util.concurrent.ConcurrentHashMap<String,Work>()
    fun leaseAlive(w:Work):Boolean=liveLeases[w.lease]==w
    private val observers = CopyOnWriteArraySet<(String,String)->Unit>()
    init {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        db = SQLiteDatabase.openOrCreateDatabase(file,null)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE IF NOT EXISTS videos(account TEXT,video TEXT,model TEXT NOT NULL DEFAULT '',epoch INTEGER NOT NULL DEFAULT 0,paused INTEGER NOT NULL DEFAULT 0,authorized INTEGER NOT NULL DEFAULT 0,root_limit INTEGER NOT NULL DEFAULT 200,reply_limit INTEGER NOT NULL DEFAULT 1000,thread_limit INTEGER NOT NULL DEFAULT 15,target TEXT NOT NULL DEFAULT 'zh-hans',total INTEGER NOT NULL DEFAULT 0,source_reply INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,video))")
        db.execSQL("CREATE TABLE IF NOT EXISTS comments(account TEXT,video TEXT,id TEXT,payload TEXT NOT NULL,source_hash TEXT NOT NULL,ordinal INTEGER NOT NULL,parent TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,video,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS translations(account TEXT,video TEXT,id TEXT,source_hash TEXT,target TEXT,text TEXT NOT NULL,PRIMARY KEY(account,video,id,source_hash,target))")
        db.execSQL("CREATE TABLE IF NOT EXISTS assets(account TEXT,video TEXT,id TEXT,payload TEXT NOT NULL,ready INTEGER NOT NULL DEFAULT 0,detail TEXT NOT NULL DEFAULT '',PRIMARY KEY(account,video,id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS streams(account TEXT,video TEXT,root TEXT,cursor INTEGER NOT NULL DEFAULT 0,terminal TEXT NOT NULL DEFAULT '',no_growth INTEGER NOT NULL DEFAULT 0,turn INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,video,root))")
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks(account TEXT,video TEXT,lane TEXT,item TEXT,version TEXT,target TEXT,status TEXT NOT NULL DEFAULT 'pending',attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,lease TEXT NOT NULL DEFAULT '',detail TEXT NOT NULL DEFAULT '',eligible INTEGER NOT NULL DEFAULT 1,turn INTEGER NOT NULL DEFAULT 0,manual INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(account,video,lane,item,version,target))")
        db.execSQL("CREATE TABLE IF NOT EXISTS lane_turns(account TEXT,video TEXT,lane TEXT,ticket INTEGER NOT NULL,PRIMARY KEY(account,video,lane))")
        // Additive only: retain every old cursor, terminal and cached row during upgrade.
        if(query("PRAGMA table_info(streams)").none { it["name"]=="detail" })
            db.execSQL("ALTER TABLE streams ADD COLUMN detail TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS migration(folder TEXT PRIMARY KEY,stamp TEXT NOT NULL,errors INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS comment_versions(account TEXT,video TEXT,id TEXT,source_hash TEXT,payload TEXT NOT NULL,PRIMARY KEY(account,video,id,source_hash))")
        db.execSQL("CREATE TABLE IF NOT EXISTS stream_seen(account TEXT,video TEXT,root TEXT,id TEXT,PRIMARY KEY(account,video,root,id))")
        // Only one repository/engine is created per host process. Process death releases abandoned leases.
        db.execSQL("UPDATE tasks SET status='pending',lease='' WHERE status='running'")
        db.execSQL("CREATE TABLE IF NOT EXISTS scope_stats(account TEXT,video TEXT,rows INTEGER NOT NULL,text_total INTEGER NOT NULL,text_ready INTEGER NOT NULL,media_total INTEGER NOT NULL,media_ready INTEGER NOT NULL,incomplete INTEGER NOT NULL,PRIMARY KEY(account,video))")
        db.execSQL("CREATE TABLE IF NOT EXISTS scope_roots(account TEXT,video TEXT,id TEXT,active INTEGER NOT NULL,PRIMARY KEY(account,video,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS task_due ON tasks(account,lane,eligible,status,retry_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS lane_ticket ON lane_turns(account,lane,ticket)")
        if(db.version<4){
            // Old native adapters used "total > page size" as truncation and prematurely stopped valid cursor streams.
            db.execSQL("UPDATE streams SET terminal='' WHERE terminal='incomplete' AND no_growth<3 AND cursor>0")
            db.version=4
        }
    }
    private fun query(sql: String, vararg args: String): List<Map<String,String>> = db.rawQuery(sql,args).use { c ->
        val out=mutableListOf<Map<String,String>>();while(c.moveToNext()) out+=c.columnNames.indices.associate { c.getColumnName(it) to (c.getString(it)?:"") };out }
    private fun exec(sql: String,vararg args: Any?) = db.execSQL(sql,args)
    private fun <T> transaction(block:()->T): T { db.beginTransaction();try { val result=block();db.setTransactionSuccessful();return result } finally { db.endTransaction() } }
    private fun video(a:String,v:String)=query("SELECT * FROM videos WHERE account=? AND video=?",a,v).singleOrNull()
    private fun emit(a:String,v:String) { observers.forEach { try { it(a,v) } catch (_:Exception) {} } }
    fun observeVideo(account:String,video:String,changed:()->Unit): AutoCloseable {
        val callback: (String,String)->Unit={a,v->if(a==account&&v==video)changed()};observers+=callback
        return AutoCloseable { observers-=callback }
    }
    @Synchronized fun accounts(): List<String> = query("SELECT DISTINCT account FROM videos").map { it.getValue("account") }
    @Synchronized fun videos(account:String): List<String> = query("SELECT video FROM videos WHERE account=? ORDER BY rowid",account).map { it.getValue("video") }
    @Synchronized fun register(account:String,video:String,model:String,quotas:OfflineCommentBudget.Quotas,target:String,authorized:Boolean=false) {
        transaction {
            exec("INSERT OR IGNORE INTO videos(account,video) VALUES(?,?)",account,video)
            val old=video(account,video)!!
            exec("UPDATE videos SET model=CASE WHEN ?='' THEN model ELSE ? END,root_limit=?,reply_limit=?,thread_limit=?,target=?,authorized=MAX(authorized,?) WHERE account=? AND video=?",
                model,model,quotas.comments,quotas.replies,quotas.perThread,target,if(authorized)1 else 0,account,video)
            if(old.getValue("root_limit").toInt()!=quotas.comments || old.getValue("reply_limit").toInt()!=quotas.replies || old.getValue("thread_limit").toInt()!=quotas.perThread)
                exec("UPDATE streams SET terminal='' WHERE account=? AND video=? AND terminal='quota'",account,video)
            for(lane in Lane.entries) {
                val next=query("SELECT COALESCE(MAX(ticket),0)+1 n FROM lane_turns WHERE account=? AND lane=?",account,lane.name).single().getValue("n").toLong()
                exec("INSERT OR IGNORE INTO lane_turns(account,video,lane,ticket) VALUES(?,?,?,?)",account,video,lane.name,next)
            }
            exec("INSERT OR IGNORE INTO streams(account,video,root) VALUES(?,?,'')",account,video)
            reconcile(account,video)
        };emit(account,video)
    }
    private fun quotas(r:Map<String,String>)=OfflineCommentBudget.Quotas(r.getValue("root_limit").toInt(),r.getValue("reply_limit").toInt(),r.getValue("thread_limit").toInt())
    private fun rows(a:String,v:String): List<OfflineCommentData.Row> {
        val target=video(a,v)?.getValue("target")?:"zh-hans"
        return query("SELECT c.payload,t.text FROM comments c LEFT JOIN translations t ON t.account=c.account AND t.video=c.video AND t.id=c.id AND t.source_hash=c.source_hash AND t.target=? WHERE c.account=? AND c.video=? ORDER BY c.ordinal,c.id",target,a,v)
            .map { row(it.getValue("payload")).copy(translation=it["text"]?.takeIf(String::isNotBlank),language=target) }
    }
    private fun selected(a:String,v:String)=video(a,v)?.let { quotas(it).select(CommentPageMerge.ordered(rows(a,v))) }.orEmpty()
    private fun task(a:String,v:String,lane:Lane,item:String,version:String="",target:String="") {
        exec("INSERT OR IGNORE INTO tasks(account,video,lane,item,version,target) VALUES(?,?,?,?,?,?)",a,v,lane.name,item,version,target)
        exec("UPDATE tasks SET eligible=1 WHERE account=? AND video=? AND lane=? AND item=? AND version=? AND target=?",a,v,lane.name,item,version,target)
    }
    /** Reconciliation never removes historical facts or tasks. Only the selected scope is made executable. */
    private fun reconcile(a:String,v:String) {
        val info=video(a,v)?:return;val scope=selected(a,v);val q=quotas(info);val target=info.getValue("target")
        exec("UPDATE tasks SET eligible=0 WHERE account=? AND video=?",a,v)
        for(r in scope) {
            if(needsTranslation(r) && r.translation.isNullOrBlank() && !r.sourceTruncated) task(a,v,Lane.TRANSLATION,r.id,hash(r.source),target)
            for(m in r.media) {
                val ready=query("SELECT ready FROM assets WHERE account=? AND video=? AND id=?",a,v,m.id).firstOrNull()?.get("ready")=="1"
                if(!ready) task(a,v,Lane.MEDIA,m.id)
            }
            if(r.parentId.isBlank() && r.replyCount>q.threadCount(scope,r.id))
                exec("INSERT OR IGNORE INTO streams(account,video,root) VALUES(?,?,?)",a,v,r.id)
        }
        val roots=scope.filter { it.parentId.isBlank() }.associateBy { it.id }
        exec("UPDATE scope_roots SET active=0 WHERE account=? AND video=?",a,v)
        // active means that this selected root still needs reply coverage in the current scope.
        // An old failed thread cannot block a fulfilled total/per-thread quota; its facts stay intact.
        roots.keys.forEach { root -> exec("INSERT OR REPLACE INTO scope_roots VALUES(?,?,?,?)",
            a,v,root,if(q.acceptsReplies(scope,root))1 else 0) }
        val verifiedRoots=query("SELECT id FROM comments WHERE account=? AND video=? AND verified=1",a,v).mapTo(hashSetOf()){it.getValue("id")}
        for(s in query("SELECT * FROM streams WHERE account=? AND video=?",a,v)) {
            val root=s.getValue("root");val terminal=s.getValue("terminal")
            val capped=if(root.isBlank()) q.rootCount(scope)>=q.comments && roots.keys.all { it in verifiedRoots } else !q.acceptsReplies(scope,root)
            if(capped && terminal.isBlank()) exec("UPDATE streams SET terminal='quota' WHERE account=? AND video=? AND root=?",a,v,root)
            if(!capped && terminal.isBlank() && (root.isBlank() || root in roots)) {
                task(a,v,Lane.SOURCE,root)
                exec("UPDATE tasks SET status='pending' WHERE account=? AND video=? AND lane='SOURCE' AND item=? AND status='done'",a,v,root)
            }
        }
        val validTokens=query("SELECT lease FROM tasks WHERE account=? AND video=? AND eligible=1 AND status='running'",a,v).mapTo(hashSetOf()){it.getValue("lease")}
        liveLeases.entries.removeIf{it.value.account==a&&it.value.video==v&&it.key !in validTokens}
        exec("UPDATE tasks SET status='pending',lease='' WHERE account=? AND video=? AND eligible=0 AND status='running'",a,v)
        val text=scope.filter{needsTranslation(it)&&!it.sourceTruncated}
        val media=scope.flatMap{it.media}.distinctBy{it.id}
        val saved=query("SELECT id FROM assets WHERE account=? AND video=? AND ready=1",a,v).mapTo(hashSetOf()){it.getValue("id")}
        exec("INSERT OR REPLACE INTO scope_stats VALUES(?,?,?,?,?,?,?,?)",a,v,scope.size,text.size,text.count{!it.translation.isNullOrBlank()},media.size,media.count{it.id in saved},if(scope.any{it.sourceTruncated||!it.mediaKnown}||q.rootCount(scope)<q.comments)1 else 0)
    }
    private fun ingest(a:String,v:String,page:OfflineCommentData.Snapshot,root:String,verified:Boolean=true) {
        require(page.account==a&&page.video==v)
        exec("UPDATE videos SET total=MAX(total,?) WHERE account=? AND video=?",if(root.isBlank())page.total else 0,a,v)
        var ordinal=query("SELECT COALESCE(MAX(ordinal),0) n FROM comments WHERE account=? AND video=?",a,v).single().getValue("n").toLong()
        for(incoming in page.rows) {
            val r=if(root.isNotBlank()&&incoming.parentId.isBlank())incoming.copy(parentId=root,depth=1) else incoming
            val old=query("SELECT payload FROM comments WHERE account=? AND video=? AND id=?",a,v,r.id).singleOrNull()?.let { row(it.getValue("payload")) }
            val merged=if(old?.source==r.source)r.copy(media=(r.media+old.media).distinctBy { it.id }.take(12),mediaKnown=r.mediaKnown||old.mediaKnown,replyCount=maxOf(r.replyCount,old.replyCount)) else r
            old?.let { exec("INSERT OR IGNORE INTO comment_versions VALUES(?,?,?,?,?)",a,v,it.id,hash(it.source),rowJson(it)) }
            exec("INSERT OR REPLACE INTO comment_versions VALUES(?,?,?,?,?)",a,v,merged.id,hash(merged.source),rowJson(merged))
            exec("INSERT OR IGNORE INTO comments(account,video,id,payload,source_hash,ordinal,parent) VALUES(?,?,?,?,?,?,?)",a,v,r.id,rowJson(merged),hash(r.source),++ordinal,merged.parentId)
            exec("UPDATE comments SET payload=?,source_hash=?,parent=? WHERE account=? AND video=? AND id=?",rowJson(merged),hash(r.source),merged.parentId,a,v,r.id)
            if(verified)exec("UPDATE comments SET verified=1 WHERE account=? AND video=? AND id=?",a,v,r.id)
            if(!r.translation.isNullOrBlank()) CommentTranslationKey.normalizedTarget(r.language)?.let { lang ->
                exec("INSERT OR REPLACE INTO translations VALUES(?,?,?,?,?,?)",a,v,r.id,hash(r.source),lang,r.translation) }
            for(m in merged.media) {
                exec("INSERT OR IGNORE INTO assets(account,video,id,payload) VALUES(?,?,?,?)",a,v,m.id,assetJson(m))
                exec("UPDATE assets SET payload=? WHERE account=? AND video=? AND id=?",assetJson(m),a,v,m.id)
                if(OfflineCommentMediaStore.file(context,a,v,m)!=null) exec("UPDATE assets SET ready=1,detail='' WHERE account=? AND video=? AND id=?",a,v,m.id)
            }
        }
    }
    @Synchronized fun importPage(page:OfflineCommentData.Snapshot,root:String="") {
        transaction { ingest(page.account,page.video,page,root);reconcile(page.account,page.video) };emit(page.account,page.video)
    }
    /** Legacy pages are read only; imported files, including corrupt ones, are never removed or rewritten. */
    fun migrateLegacy(account:String,target:String,onlyVideo:String?=null,quotas:(String)->OfflineCommentBudget.Quotas) {
        val base=File(context.noBackupFilesDir,"toki-offline-comments-v2")
        val folders=if(onlyVideo==null || account==HostOfflineComments.LOCAL_SCOPE)base.listFiles().orEmpty().toList() else listOf(File(base,CommentSnapshotStore.key(account,onlyVideo)))
        for(folder in folders.filter{it.isDirectory&&it.name.matches(Regex("[a-f0-9]{64}"))}) importLegacyFolder(folder,account,target,onlyVideo,quotas)
    }
    @Synchronized private fun importLegacyFolder(folder:File,account:String,target:String,onlyVideo:String?,quotas:(String)->OfflineCommentBudget.Quotas) {
            val indexFile=File(folder,"index.json");if(!indexFile.isFile)return
            val stamp="${indexFile.lastModified()}:${indexFile.length()}"
            val checkpoint=if(account==HostOfflineComments.LOCAL_SCOPE)folder.name+":"+account else folder.name
            if(query("SELECT stamp FROM migration WHERE folder=?",checkpoint).firstOrNull()?.get("stamp")==stamp)return
            val index=try { JSONObject(indexFile.readText()) } catch(_:Exception) { return }
            val legacy=index.optString("account")
            if(account!=HostOfflineComments.LOCAL_SCOPE && legacy!=account)return
            val v=index.optString("video");if(v.isBlank() || onlyVideo!=null&&v!=onlyVideo)return
            if(account==HostOfflineComments.LOCAL_SCOPE)OfflineCommentMediaStore.referenceLegacy(context,account,v,legacy)
            transaction {
                val model=try { File(folder,"model.json").readText() } catch(_:Exception) { "" }
                register(account,v,model,quotas(v),target)
                var errors=0;val pages=index.optJSONObject("pages")?:JSONObject()
                val names=pages.keys().asSequence().toList().sortedWith(compareBy<String>{pages.getJSONObject(it).optString("root")}.thenBy{pages.getJSONObject(it).optLong("cursor")})
                for(name in names) try {
                    require(name.matches(Regex("[a-f0-9]{64}")))
                    val bytes=android.util.AtomicFile(File(folder,"$name.bin")).openRead().use { it.readBytes() }
                    val p=CommentSnapshotCodec.decode(bytes);val root=pages.getJSONObject(name).optString("root")
                    val verified=pages.getJSONObject(name).optInt("replyTotalsVersion")==1
                    val restored=CommentTranslationStore.restore(context,p,target)
                    ingest(account,v,restored.copy(account=account),root,verified)
                    if(verified) {
                        exec("INSERT OR IGNORE INTO streams(account,video,root) VALUES(?,?,?)",account,v,root)
                        val current=query("SELECT cursor,terminal FROM streams WHERE account=? AND video=? AND root=?",account,v,root).single()
                        if(current.getValue("terminal").isBlank() && current.getValue("cursor").toLong()==pages.getJSONObject(name).optLong("cursor")) {
                            val next=p.nextCursor
                            exec("UPDATE streams SET cursor=?,terminal=? WHERE account=? AND video=? AND root=?",next?:current.getValue("cursor").toLong(),if(!p.hasMore)"ended"else"",account,v,root)
                        }
                        p.rows.forEach { exec("INSERT OR IGNORE INTO stream_seen VALUES(?,?,?,?)",account,v,root,it.id) }
                    }
                } catch(_:Exception) { errors++ }
                reconcile(account,v)
                exec("INSERT OR REPLACE INTO migration VALUES(?,?,?)",checkpoint,stamp,errors)
                if(errors>0)exec("UPDATE scope_stats SET incomplete=1 WHERE account=? AND video=?",account,v)
            };emit(account,v)
    }
    fun migrateLocalScope(scope:String,onlyVideo:String?=null) {
        require(scope==HostOfflineComments.LOCAL_SCOPE)
        val keys=synchronized(this){query("SELECT account,video FROM videos WHERE account<>?",scope).filter{onlyVideo==null||it["video"]==onlyVideo}}
        for(key in keys)copyLocalVideo(key.getValue("account"),key.getValue("video"),scope)
    }
    @Synchronized private fun copyLocalVideo(old:String,v:String,scope:String) {
        val marker="sqlite-local:"+old+":"+v
        if(query("SELECT folder FROM migration WHERE folder=?",marker).isNotEmpty())return
        val info=video(old,v)?:return
        OfflineCommentMediaStore.referenceLegacy(context,scope,v,old)
        transaction {
            if(OfflineCommentLimitOverrides.savedLimit(context,scope,v)==null)OfflineCommentLimitOverrides.savedLimit(context,old,v)?.let{OfflineCommentLimitOverrides.set(context,scope,v,it)}
            register(scope,v,info.getValue("model"),quotas(info),info.getValue("target"))
            val source=rows(old,v)
            ingest(scope,v,OfflineCommentData.Snapshot(scope,v,0,source,info.getValue("total").toLong(),false,false),"",false)
            exec("INSERT OR IGNORE INTO translations SELECT ?,video,id,source_hash,target,text FROM translations WHERE account=? AND video=?",scope,old,v)
            exec("INSERT OR IGNORE INTO comment_versions SELECT ?,video,id,source_hash,payload FROM comment_versions WHERE account=? AND video=?",scope,old,v)
            exec("UPDATE comments SET verified=1 WHERE account=? AND video=? AND id IN(SELECT id FROM comments WHERE account=? AND video=? AND verified=1)",scope,v,old,v)
            exec("UPDATE videos SET total=MAX(total,?) WHERE account=? AND video=?",info.getValue("total").toLong(),scope,v)
            for(stream in query("SELECT * FROM streams WHERE account=? AND video=?",old,v)) {
                exec("INSERT OR IGNORE INTO streams(account,video,root,cursor,terminal,no_growth,turn,detail) VALUES(?,?,?,?,?,?,?,?)",scope,v,stream.getValue("root"),stream.getValue("cursor").toLong(),stream.getValue("terminal"),stream.getValue("no_growth").toInt(),stream.getValue("turn").toLong(),stream.getValue("detail"))
                exec("UPDATE streams SET cursor=?,terminal=?,no_growth=?,detail=? WHERE account=? AND video=? AND root=? AND cursor=0 AND terminal=''",stream.getValue("cursor").toLong(),stream.getValue("terminal"),stream.getValue("no_growth").toInt(),stream.getValue("detail"),scope,v,stream.getValue("root"))
            }
            exec("INSERT OR IGNORE INTO stream_seen SELECT ?,video,root,id FROM stream_seen WHERE account=? AND video=?",scope,old,v)
            reconcile(scope,v)
            exec("INSERT OR REPLACE INTO migration VALUES(?,'imported',0)",marker)
        };emit(scope,v)
    }
    /** Diagnostics and wake scheduling never decode comment bodies or scan every video under the repository monitor. */
    @Synchronized fun progressCounts(account:String):Map<String,Int> {
        val states=query("""SELECT v.video,v.paused,COALESCE(z.rows,0) rows,COALESCE(z.text_total,0) text_total,COALESCE(z.text_ready,0) text_ready,
            COALESCE(z.media_total,0) media_total,COALESCE(z.media_ready,0) media_ready,COALESCE(z.incomplete,0) incomplete,
            (SELECT COUNT(*) FROM streams s WHERE s.account=v.account AND s.video=v.video AND (s.root='' OR EXISTS(SELECT 1 FROM scope_roots sr WHERE sr.account=s.account AND sr.video=s.video AND sr.id=s.root AND sr.active=1))) stream_count,
            (SELECT COUNT(*) FROM streams s WHERE s.account=v.account AND s.video=v.video AND s.terminal='' AND (s.root='' OR EXISTS(SELECT 1 FROM scope_roots sr WHERE sr.account=s.account AND sr.video=s.video AND sr.id=s.root AND sr.active=1))) open_streams,
            (SELECT COUNT(*) FROM streams s WHERE s.account=v.account AND s.video=v.video AND s.terminal='incomplete' AND (s.root='' OR EXISTS(SELECT 1 FROM scope_roots sr WHERE sr.account=s.account AND sr.video=s.video AND sr.id=s.root AND sr.active=1))) partial_streams,
            (SELECT COUNT(*) FROM tasks t WHERE t.account=v.account AND t.video=v.video AND t.eligible=1 AND t.status='running') running,
            (SELECT COUNT(*) FROM tasks t WHERE t.account=v.account AND t.video=v.video AND t.eligible=1 AND t.status='retry') retry
            FROM videos v LEFT JOIN scope_stats z ON z.account=v.account AND z.video=v.video WHERE v.account=?""",account)
        fun Map<String,String>.n(key:String)=getValue(key).toInt()
        fun Map<String,String>.sourceDone()=n("stream_count")>0&&n("open_streams")==0
        fun Map<String,String>.prepared()=sourceDone()&&n("text_total")==n("text_ready")&&n("media_total")==n("media_ready")
        fun Map<String,String>.partial()=n("incomplete")>0||n("partial_streams")>0
        return mapOf("videos" to states.size,"originals" to states.count{it.n("rows")>0},"sourcesReady" to states.count{it.sourceDone()},
            "mediaReady" to states.count{it.n("stream_count")>0&&it.n("media_total")==it.n("media_ready")},
            "ready" to states.count{it["paused"]!="1"&&it.n("running")==0&&it.prepared()&&!it.partial()},
            "partial" to states.count{it["paused"]!="1"&&it.n("running")==0&&it.prepared()&&it.partial()},
            "retrying" to states.count{it["paused"]!="1"&&it.n("retry")>0},
            "queued" to states.count{it["paused"]!="1"&&it.n("running")==0&&!it.prepared()&&it.n("retry")==0})
    }
    @Synchronized fun nextDelay(account:String,lane:Lane,now:Long,backgroundAllowed:Boolean):Long? {
        val time=query("SELECT MIN(t.retry_at) due FROM tasks t JOIN videos v ON v.account=t.account AND v.video=t.video WHERE t.account=? AND t.lane=? AND t.eligible=1 AND t.status IN ('pending','retry') AND v.paused=0 AND v.model<>'' AND NOT EXISTS(SELECT 1 FROM tasks flight WHERE flight.account=t.account AND flight.video=t.video AND flight.lane=t.lane AND flight.status='running') AND (v.authorized=1 OR t.manual=1 OR ?='1')",account,lane.name,if(backgroundAllowed)"1"else"0").single()["due"]?.toLongOrNull()?:return null
        return (time-now).coerceAtLeast(250)
    }
    @Synchronized fun claim(account:String,lane:Lane,now:Long,backgroundAllowed:Boolean): Work? = transaction {
        val t=query("SELECT t.*,v.epoch,v.model,s.cursor FROM tasks t JOIN videos v ON v.account=t.account AND v.video=t.video JOIN lane_turns q ON q.account=t.account AND q.video=t.video AND q.lane=t.lane LEFT JOIN streams s ON s.account=t.account AND s.video=t.video AND s.root=t.item WHERE t.account=? AND t.lane=? AND t.eligible=1 AND t.status IN ('pending','retry') AND t.retry_at<=? AND v.paused=0 AND (v.authorized=1 OR t.manual=1 OR ?='1') AND v.model<>'' AND NOT EXISTS(SELECT 1 FROM tasks flight WHERE flight.account=t.account AND flight.video=t.video AND flight.lane=t.lane AND flight.status='running') ORDER BY q.ticket,CASE WHEN t.lane='SOURCE' AND ((t.item='' AND v.source_reply=0) OR (t.item<>'' AND v.source_reply=1)) THEN 0 ELSE 1 END,t.turn,t.rowid LIMIT 1",account,lane.name,now.toString(),if(backgroundAllowed)"1" else "0").firstOrNull()?:return@transaction null
        val v=t.getValue("video");val item=t.getValue("item");val token=UUID.randomUUID().toString()
        val next=query("SELECT COALESCE(MAX(ticket),0)+1 n FROM lane_turns WHERE account=? AND lane=?",account,lane.name).single().getValue("n").toLong()
        exec("UPDATE lane_turns SET ticket=? WHERE account=? AND video=? AND lane=?",next,account,v,lane.name)
        exec("UPDATE tasks SET status='running',lease=?,detail='',turn=? WHERE account=? AND video=? AND lane=? AND item=? AND version=? AND target=?",token,next,account,v,lane.name,item,t.getValue("version"),t.getValue("target"))
        if(lane==Lane.SOURCE)exec("UPDATE videos SET source_reply=? WHERE account=? AND video=?",if(item.isBlank())1 else 0,account,v)
        // Eligibility is already reconciled transactionally when source, language or quotas change.
        // Claim one body by its stable key, never decode the whole video under the shared monitor.
        val row=if(lane==Lane.TRANSLATION)query("SELECT payload FROM comments WHERE account=? AND video=? AND id=? AND source_hash=?",account,v,item,t.getValue("version"))
            .singleOrNull()?.let { row(it.getValue("payload")) } else null
        val asset=if(lane==Lane.MEDIA)query("SELECT payload FROM assets WHERE account=? AND video=? AND id=?",account,v,item).singleOrNull()?.let { asset(it.getValue("payload")) } else null
        Work(account,v,lane,item,t.getValue("version"),t.getValue("target"),token,t.getValue("epoch").toLong(),t["cursor"]?.toLongOrNull()?:0,t.getValue("model"),row,asset,t.getValue("manual")=="1"||video(account,v)?.get("authorized")=="1").also{liveLeases[token]=it}
    }.also { if(it!=null)emit(it.account,it.video) }
    @Synchronized fun authorized(w:Work):Boolean = video(w.account,w.video)?.get("authorized")=="1" ||
        query("SELECT manual FROM tasks WHERE lease=?",w.lease).firstOrNull()?.get("manual")=="1"
    @Synchronized fun active(w:Work):Boolean = query("SELECT t.lease FROM tasks t JOIN videos v ON v.account=t.account AND v.video=t.video WHERE t.account=? AND t.video=? AND t.lane=? AND t.item=? AND t.version=? AND t.target=? AND t.status='running' AND t.eligible=1 AND v.epoch=? AND v.paused=0",w.account,w.video,w.lane.name,w.item,w.version,w.target,w.epoch.toString()).firstOrNull()?.get("lease")==w.lease
    private fun finish(w:Work,status:String,detail:String="",retry:Long=0,attempts:Int=0) {
        liveLeases.remove(w.lease)
        exec("UPDATE tasks SET status=?,detail=?,retry_at=?,attempts=?,lease='' WHERE account=? AND video=? AND lane=? AND item=? AND version=? AND target=? AND lease=?",status,detail,retry,attempts,w.account,w.video,w.lane.name,w.item,w.version,w.target,w.lease)
    }
    @Synchronized fun commitPage(w:Work,page:OfflineCommentData.Snapshot):Boolean {
        if(!active(w))return false
        transaction {
            val before=query("SELECT id FROM stream_seen WHERE account=? AND video=? AND root=?",w.account,w.video,w.item).mapTo(hashSetOf()){it.getValue("id")}
            ingest(w.account,w.video,page,w.item)
            val streamRows=page.rows.filter { if(w.item.isBlank())it.parentId.isBlank()&&it.depth==0 else it.id!=w.item }
            val growth=streamRows.count { it.id !in before }
            streamRows.forEach { exec("INSERT OR IGNORE INTO stream_seen VALUES(?,?,?,?)",w.account,w.video,w.item,it.id) }
            val s=query("SELECT * FROM streams WHERE account=? AND video=? AND root=?",w.account,w.video,w.item).single()
            val noGrowth=if(growth==0)s.getValue("no_growth").toInt()+1 else 0
            val next=page.nextCursor?.takeIf { page.hasMore&&it>w.cursor }
            val info=video(w.account,w.video)!!;val scope=selected(w.account,w.video);val limits=quotas(info)
            val expected=scope.firstOrNull{it.id==w.item}?.replyCount?:0
            val insufficientReply=w.item.isNotBlank()&&!page.hasMore&&limits.threadCount(scope,w.item)<minOf(expected,limits.perThread.toLong())&&limits.replyCount(scope)<limits.replies
            val terminal=when { page.hasMore&&(noGrowth>=3||next==null)->"incomplete";!page.hasMore&&(page.truncated||insufficientReply)->"incomplete";!page.hasMore->"ended";else->"" }
            val detail=when {
                page.hasMore&&noGrowth>=3 -> "no_growth_three_pages"
                page.hasMore&&next==null -> "cursor_not_advancing"
                !page.hasMore&&page.truncated -> "response_incomplete"
                insufficientReply -> "reply_shortfall"
                terminal=="ended" -> "server_ended"
                else -> ""
            }
            exec("UPDATE streams SET cursor=?,terminal=?,no_growth=?,detail=? WHERE account=? AND video=? AND root=?",next?:w.cursor,terminal,noGrowth,detail,w.account,w.video,w.item)
            finish(w,if(terminal.isBlank())"pending" else "done")
            reconcile(w.account,w.video)
        };emit(w.account,w.video);return true
    }
    @Synchronized fun commitTranslation(w:Work,text:String):Boolean {
        if(!active(w)||text.isBlank()||text.length>80000)return false
        val current=query("SELECT source_hash FROM comments WHERE account=? AND video=? AND id=?",w.account,w.video,w.item).firstOrNull()?.get("source_hash")
        if(current!=w.version)return false
        transaction {
            val alreadySaved=query("SELECT text FROM translations WHERE account=? AND video=? AND id=? AND source_hash=? AND target=?",w.account,w.video,w.item,w.version,w.target)
                .singleOrNull()?.get("text")?.isNotBlank()==true
            exec("INSERT OR REPLACE INTO translations VALUES(?,?,?,?,?,?)",w.account,w.video,w.item,w.version,w.target,text)
            finish(w,"done")
            exec("UPDATE tasks SET eligible=0 WHERE account=? AND video=? AND lane='TRANSLATION' AND item=? AND version=? AND target=?",w.account,w.video,w.item,w.version,w.target)
            // A valid lease proves this body is in the current scope. Text completion changes no
            // comment relation, source cursor or media task; keep those facts and leases untouched.
            if(!alreadySaved)exec("UPDATE scope_stats SET text_ready=MIN(text_total,text_ready+1) WHERE account=? AND video=?",w.account,w.video)
        }
        emit(w.account,w.video);return true
    }
    @Synchronized fun commitMedia(w:Work):Boolean {
        if(!active(w)||w.asset==null||OfflineCommentMediaStore.file(context,w.account,w.video,w.asset)==null)return false
        transaction { exec("UPDATE assets SET ready=1,detail='' WHERE account=? AND video=? AND id=?",w.account,w.video,w.item);finish(w,"done");reconcile(w.account,w.video) };emit(w.account,w.video);return true
    }
    @Synchronized fun fail(w:Work,error:Throwable,now:Long) {
        if(!active(w)) { release(w);return }
        val attempts=query("SELECT attempts FROM tasks WHERE lease=?",w.lease).single().getValue("attempts").toInt()+1
        val detail=OfflineCommentRequestFailure.describe(w.lane.name.lowercase(),error)
        finish(w,"retry",detail,now+OfflineCommentWorkPlan.retryDelay(attempts),attempts);emit(w.account,w.video)
    }
    @Synchronized fun release(w:Work) { finish(w,"pending");emit(w.account,w.video) }
    @Synchronized fun submitCommand(account:String,video:String,command:Command) {
        transaction {
            when(command) {
                Command.Pause -> { liveLeases.entries.removeIf{it.value.account==account&&it.value.video==video};exec("UPDATE videos SET paused=1,epoch=epoch+1 WHERE account=? AND video=?",account,video);exec("UPDATE tasks SET status='pending',lease='' WHERE account=? AND video=? AND status='running'",account,video) }
                Command.Prepare,Command.Resume -> exec("UPDATE videos SET paused=0,authorized=1 WHERE account=? AND video=?",account,video)
                Command.Retry -> {
                    exec("UPDATE videos SET paused=0,authorized=1 WHERE account=? AND video=?",account,video)
                    exec("UPDATE tasks SET status='pending',retry_at=0,detail='' WHERE account=? AND video=? AND status='retry'",account,video)
                    exec("UPDATE streams SET terminal='',no_growth=0 WHERE account=? AND video=? AND terminal='incomplete'",account,video)
                }
                is Command.Translate -> { /* Enqueue the video's missing text without bypassing the fair lane. */
                    exec("UPDATE videos SET paused=0 WHERE account=? AND video=?",account,video)
                    exec("UPDATE tasks SET manual=1,retry_at=0,detail='',status=CASE WHEN status='retry' THEN 'pending' ELSE status END WHERE account=? AND video=? AND lane='TRANSLATION' AND item=? AND eligible=1",account,video,command.comment)
                }
            };reconcile(account,video)
        };emit(account,video)
    }
    /** Invalidates future writes, without deleting any cache, in response to a host clear/account barrier. */
    @Synchronized fun suspendAccount(account:String) {
        liveLeases.entries.removeIf{it.value.account==account}
        exec("UPDATE videos SET paused=1,authorized=0,epoch=epoch+1 WHERE account=?",account)
        exec("UPDATE tasks SET status=CASE WHEN status='running' THEN 'pending' ELSE status END,lease='',manual=0 WHERE account=?",account)
    }
    @Synchronized fun readThread(account:String,video:String,root:String):List<OfflineCommentData.Row> {
        val list=selected(account,video);val byId=list.associateBy { it.id }
        return list.filter { r -> var p=r.parentId;var n=0;while(p.isNotBlank()&&p!=root&&n++<4)p=byId[p]?.parentId.orEmpty();r.parentId.isNotBlank()&&p==root }
    }
    @Synchronized fun readVideo(account:String,video:String):VideoState? {
        val info=video(account,video)?:return null;val all=rows(account,video);val scope=quotas(info).select(CommentPageMerge.ordered(all))
        val work=query("SELECT * FROM tasks WHERE account=? AND video=? AND eligible=1",account,video)
        fun states(lane:Lane)=work.filter { it["lane"]==lane.name }.associate { it.getValue("item") to ItemState(it.getValue("status"),it.getValue("detail"),it.getValue("retry_at").toLong()) }
        val text=scope.filter { needsTranslation(it)&&!it.sourceTruncated };val translated=text.count { !it.translation.isNullOrBlank() }
        val assets=scope.flatMap{it.media}.distinctBy{it.id};val ready=assets.count { m -> query("SELECT ready FROM assets WHERE account=? AND video=? AND id=?",account,video,m.id).firstOrNull()?.get("ready")=="1" }
        val streams=query("SELECT * FROM streams WHERE account=? AND video=?",account,video)
        val rootIds=scope.filter { it.parentId.isBlank() }.mapTo(hashSetOf()){it.id}
        val limits=quotas(info)
        val relevant=streams.filter { stream -> val root=stream.getValue("root")
            root.isBlank()||root in rootIds&&limits.acceptsReplies(scope,root) }
        val sourceDone=relevant.isNotEmpty()&&relevant.all { it.getValue("terminal").isNotBlank() }
        val errors=query("SELECT errors FROM migration WHERE folder=?",CommentSnapshotStore.key(account,video)).firstOrNull()?.get("errors")?.toIntOrNull()?:0
        val incomplete=errors>0||relevant.any { it["terminal"]=="incomplete" }||scope.any{it.sourceTruncated||!it.mediaKnown}||quotas(info).rootCount(scope)<quotas(info).comments
        val phase=when { info["paused"]=="1"->"paused";work.any{it["status"]=="running"}->"running";sourceDone&&translated==text.size&&ready==assets.size->if(incomplete)"partial" else "ready";work.any{it["status"]=="retry"}->"retry";else->"queued" }
        val reasons=mutableListOf<String>()
        if(errors>0)reasons+="migration_errors:$errors"
        val roots=limits.rootCount(scope)
        if(roots<limits.comments)reasons+="root_shortfall:$roots/${limits.comments}"
        scope.count{it.sourceTruncated}.takeIf{it>0}?.let{reasons+="truncated_bodies:$it"}
        scope.count{!it.mediaKnown}.takeIf{it>0}?.let{reasons+="unknown_media:$it"}
        relevant.filter{it["terminal"]=="incomplete"}.forEach {
            val detail=it.getValue("detail").ifBlank {
                if(it.getValue("no_growth").toInt()>=3)"no_growth_three_pages"else"legacy_coverage_incomplete"
            }
            if(detail !in reasons)reasons+=detail
        }
        val reason=when { reasons.isNotEmpty()->reasons.joinToString(";");relevant.any{it["terminal"]=="quota"}->"quota";sourceDone->"server_ended";else->"fetching" }
        return VideoState(OfflineCommentData.Snapshot(account,video,System.currentTimeMillis(),scope,info.getValue("total").toLong(),!sourceDone,incomplete),all.count{it.parentId.isBlank()},all.count{it.parentId.isNotBlank()},translated,text.size,ready,assets.size,phase,reason,info["paused"]=="1",info["authorized"]=="1",states(Lane.TRANSLATION),states(Lane.MEDIA),errors)
    }
    @Synchronized override fun close() { liveLeases.clear();db.close() }
}
