package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Modifier

/** Download-bound durable jobs. Does not depend on visible playback, dialog lifetime or the old translation toggle. */
internal object OfflineCommentDownloadHook {
    const val FEATURE="OfflineCommentDownloadHook"
    const val KEY="offline_comment_download_cache"
    const val BACKFILL="offline_comment_backfill_active"
    const val PASSIVE="offline_comment_backfill_passive"
    private val running=AtomicBoolean(false)

    private var enqueueVisible:((Any)->Unit)?=null
    fun passive(model:Any){if(ConfigClient.isReady&&ConfigClient.getBoolean(PASSIVE))enqueueVisible?.invoke(model)}
    private fun prefs(context:Context)=context.getSharedPreferences("toki_offline_comment_jobs_v1",Context.MODE_PRIVATE)
    fun networkAllowed():Boolean {
        if(!ConfigClient.isReady||!ConfigClient.getBoolean(KEY))return false
        return try{
            val manager=HookRuntime.hostContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager?:return false
            val caps=manager.getNetworkCapabilities(manager.activeNetwork?:return false)?:return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }catch(_:Exception){false}
    }
    fun capture()=LocalOnlyRequest{networkAllowed()}
    fun init(module:XposedModule,loader:ClassLoader){
        val app=checkNotNull(HookRuntime.hostContext);val main=Handler(Looper.getMainLooper())
        val worker=Executors.newSingleThreadExecutor{task->Thread(task,"toki-offline-comment-download").apply{isDaemon=true}}
        val reader=HostOfflineComments(loader);val network=HostCommentPageNetwork(loader);val replies=HostCommentReplyNetwork(loader)
        val aweme=Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme",false,loader)
        val id=aweme.getMethod("getAid");val repository=Class.forName("X.0A6x",false,loader)
        val list=repository.getDeclaredMethod("LJIJI").apply{check(Modifier.isStatic(modifiers)&&List::class.java.isAssignableFrom(returnType))}
        val completion=Class.forName("X.0rF7",false,loader).getDeclaredMethod("LIZ",aweme,String::class.java).apply{
            check(Modifier.isStatic(modifiers)&&returnType==Void.TYPE);isAccessible=true
        }
        val transport by lazy{NativeSubtitleTranslator.transport(loader,::capture)}
        val facade by lazy{loader.adaptiveClass("X.03hV")};val service by lazy{facade.adaptiveDeclaredField("LIZIZ")}
        val language by lazy{facade.adaptiveDeclaredMethod("LLILLIZIL")}
        fun target():String=CommentTranslationKey.normalizedTarget(language.invoke(service.get(null)) as String)?:error("Translation target unavailable")
        val closedFlag=AtomicBoolean(false);val readyFlag=AtomicBoolean(false)
        fun closed()=closedFlag.get()
        fun ready()=readyFlag.get()
        fun record(model:Any){
            if(closed()||!ready()||!ConfigClient.isReady||!ConfigClient.getBoolean(KEY)||!aweme.isInstance(model))return
            try{
                val video=id.invoke(model) as? String?:return
                val account=reader.account(app);val key=CommentSnapshotStore.key(account,video)
                var previous=prefs(app).getString(key,null)?.let(::JSONObject)
                val nowTarget=try{target()}catch(_:Exception){null}
                if(nowTarget!=null&&previous?.optString("target")?.takeIf{it.isNotBlank()}?.let{it!=nowTarget}==true)previous=null
                if(previous?.optString("state")=="complete"||previous?.optString("state")=="pending")return
                val job=previous?:JSONObject().put("account",account).put("video",video).put("cursor",0L).put("roots",JSONArray())
                job.put("epoch",prefs(app).getLong("epoch:"+account,0L))
                OfflineCommentArchive.saveModel(app,account,video,reader.serializeModel(model))
                job.put("state","pending").put("attempts",0)
                check(prefs(app).edit().putString(key,job.toString()).commit())
                OfflineCommentArchive.status(app,account,video,"pending")
            }catch(e:Exception){HookRuntime.event(FEATURE,"下载任务记录失败 type="+e.javaClass.simpleName)}
        }
        fun <T> await(start:((Result<T>)->Unit)->AutoCloseable):T{
            val latch=CountDownLatch(1);var result:Result<T>?=null
            val handle=start{value->result=value;latch.countDown()}
            try{check(latch.await(20,TimeUnit.SECONDS)){"Offline comment request timeout"};return checkNotNull(result).getOrThrow()}
            finally{handle.close()}
        }
        fun translatePage(page:OfflineCommentData.Snapshot,lang:String,cursor:Long,root:String,active:()->Boolean):OfflineCommentData.Snapshot{
            val rows=page.rows.toMutableList()
            for(i in rows.indices){
                check(active());val row=rows[i]
                if(row.source.isBlank())continue
                require(!row.sourceTruncated){"Comment source too long; original is incomplete"}
                if(!row.translation.isNullOrBlank()&&CommentTranslationKey.normalizedTarget(row.language)==lang)continue
                val translated=await<String>{done->
                    var task:ManualCommentTranslation?=null
                    val cancelled=AtomicBoolean(false)
                    main.post{
                        if(cancelled.get()||!active()){done(Result.failure(IllegalStateException("Cancelled")));return@post}
                        val once=AtomicBoolean(false)
                        task=ManualCommentTranslation(translate={text,complete->
                            if(cancelled.get()||!active()){complete(null);AutoCloseable{}}else transport(text,complete)
                        },post={r,delay->main.postDelayed(r,delay);Unit},remove={main.removeCallbacks(it)},changed={state->
                            if(!state.busy&&once.compareAndSet(false,true)){
                                val value=state.translated?:if(state.failed==0&&state.completed==state.total)state.original else null
                                done(if(value!=null&&state.failed==0)Result.success(value) else Result.failure(IllegalStateException("Translation incomplete")))
                            }
                        })
                        task?.start(row.source)
                    }
                    AutoCloseable{cancelled.set(true);main.post{task?.close()}}
                }
                check(active());rows[i]=row.copy(translation=translated,language=lang)
                // Checkpoint every finished row so a process exit cannot discard already translated comments.
                OfflineCommentArchive.save(app,page.copy(rows=rows.toList()),cursor,root,active)
            }
            return page.copy(rows=rows.toList())
        }
        fun execute(key:String,job:JSONObject){
            val account=job.getString("account");val video=job.getString("video");val model=reader.parseModel(OfflineCommentArchive.model(app,account,video))
            val lang=job.optString("target").takeIf{it.isNotBlank()}?:target().also{job.put("target",it)}
            fun active()=!closed()&&networkAllowed()&&reader.accountMatches(account)&&target()==lang&&
                prefs(app).getLong("epoch:"+account,0L)==job.optLong("epoch")
            check(active())
            val cursor=job.getLong("cursor");val roots=job.getJSONArray("roots")
            val root=if(job.optBoolean("rootPhase")&&roots.length()>0)roots.getJSONObject(0).getString("id") else ""
            val position=if(root.isNotEmpty())roots.getJSONObject(0).getLong("cursor") else cursor
            OfflineCommentArchive.status(app,account,video,"fetching")
            val remote=capture();val op=CommentPageOperation()
            val saved=OfflineCommentArchive.page(app,account,video,position,root)
            val rawPage=saved?:await<Any>{done->
                if(root.isEmpty())network.fetch(app,reader,video,model,position,account,remote,op,done)
                else replies.fetch(app,reader,video,model,root,position,account,remote,op,done)
                op
            }.let{reader.copyResponse(app,video,account,it,System.currentTimeMillis(),root.takeIf{it.isNotEmpty()},::active)}
            val page=if(root.isEmpty()||saved!=null)rawPage else rawPage.copy(rows=rawPage.rows.map{
                it.copy(depth=(it.depth+1).coerceAtMost(3),parentId=it.parentId.ifBlank{root})
            })
            check(active());OfflineCommentArchive.save(app,page,position,root,::active)
            OfflineCommentArchive.status(app,account,video,"translating")
            val translated=translatePage(page,lang,position,root,::active)
            OfflineCommentArchive.save(app,translated,position,root,::active)
            val nextCursor=OfflineCommentDownloadProgress.advance(position,page)
            require(OfflineCommentDownloadProgress.translated(translated,lang)){"Some translations missing"}
            if(root.isEmpty()){
                val known=(0 until roots.length()).map{roots.getJSONObject(it).getString("id")}.toMutableSet()
                page.rows.filter{it.depth==0&&it.parentId.isBlank()&&it.replyCount>page.rows.count{child->child.parentId==it.id}}.forEach{
                    if(known.add(it.id))roots.put(JSONObject().put("id",it.id).put("cursor",0L))
                }
                if(nextCursor!=null)job.put("cursor",nextCursor) else job.put("rootPhase",true)
            }else{
                if(nextCursor!=null)roots.getJSONObject(0).put("cursor",nextCursor)
                else{val remaining=JSONArray();for(i in 1 until roots.length())remaining.put(roots.get(i));job.put("roots",remaining)}
            }
            val complete=job.optBoolean("rootPhase")&&job.getJSONArray("roots").length()==0
            if(complete)check(OfflineCommentArchive.coverage(app,account,video)){"Server totals exceed saved comment coverage"}
            job.put("state",if(complete)"complete" else "pending").put("attempts",0)
            check(active())
            check(prefs(app).edit().putString(key,job.toString()).commit())
            OfflineCommentArchive.status(app,account,video,if(complete)"complete" else "pending")
            HookRuntime.count(FEATURE,if(complete)"原文与译文下载阶段缓存完成" else "已持久保存一页原文与译文")
        }
        lateinit var pump:()->Unit
        pump={
            if(!closed()&&ready()&&networkAllowed()&&running.compareAndSet(false,true))worker.execute{
                var more=false
                try{
                    val account=reader.account(app)
                    val entries=prefs(app).all.entries.sortedBy { (_,v)->try{JSONObject(v as String).optLong("lastRun")}catch(_:Exception){Long.MAX_VALUE} }
                    for((key,value)in entries){
                        val job=try{JSONObject(value as String)}catch(_:Exception){continue}
                        if(job.optString("account")!=account||job.optString("state")=="complete"||job.optInt("attempts")>=3||job.optLong("retryAt")>System.currentTimeMillis())continue
                        job.put("lastRun",System.currentTimeMillis())
                        try{execute(key,job)}catch(e:Exception){
                            job.put("state","partial").put("attempts",job.optInt("attempts")+1).put("retryAt",System.currentTimeMillis()+5000)
                            if(prefs(app).getLong("epoch:"+account,0L)==job.optLong("epoch")) {
                                prefs(app).edit().putString(key,job.toString()).commit()
                                OfflineCommentArchive.status(app,account,job.getString("video"),"partial",e.javaClass.simpleName)
                            }
                            HookRuntime.event(FEATURE,"评论预缓存暂停 type="+e.javaClass.simpleName)
                        }
                        more=true;break // one bounded page per scheduling turn; durable list has no drop-oldest queue
                    }
                }catch(e:Exception){HookRuntime.event(FEATURE,"下载缓存调度失败 type="+e.javaClass.simpleName)}
                finally{running.set(false);if(more&&!closed())main.postDelayed({pump()},5000)}
            }
        }
        enqueueVisible={model->if(!closed())worker.execute{record(model);main.post{pump()}}}
        module.trackHook(FEATURE,completion,requiresConfiguration=false).intercept{chain->
            val model=chain.args[0];val result=chain.proceed()
            if(model!=null&&!closed()&&ConfigClient.isReady&&ConfigClient.getBoolean(KEY))worker.execute{record(model);main.post{pump()}}
            result
        }
        module.deoptimize(completion)
        // This event is emitted by the inspected native clearUserCache path only for CLICK_CLEAR_ICON.
        // Do not invoke it here or react to automatic per-video invalidation/low-storage deletion.
        val clearReason=Class.forName("X.0A9I",false,loader)
        val manualClear=clearReason.enumConstants!!.single{(it as Enum<*>).name=="CLICK_CLEAR_ICON"}
        val clearEvent=Class.forName("X.0A9A",false,loader).getDeclaredMethod("LJIIIZ",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,clearReason)
        module.trackHook(FEATURE,clearEvent,requiresConfiguration=false).intercept{chain->
            val result=chain.proceed()
            if(ready()&&chain.args[2]===manualClear) {
                val account=reader.account(app)
                // Epoch barrier is synchronous; outstanding fetch/translation callbacks cannot repopulate cleared data.
                check(prefs(app).edit().putLong("epoch:"+account,prefs(app).getLong("epoch:"+account,0L)+1).commit())
                worker.execute {
                    val edit=prefs(app).edit()
                    for((key,value)in prefs(app).all)try{if(JSONObject(value as String).optString("account")==account)edit.remove(key)}catch(_:Exception){}
                    edit.commit()
                    OfflineCommentArchive.clearAccountAfterManualClear(app,account)
                    HookRuntime.count(FEATURE,"用户手动清空全部离线视频后清理同账号评论伴随缓存")
                }
            }
            result
        }

        val scanning=AtomicBoolean(false)
        fun backfill(){if(ready()&&ConfigClient.isReady&&ConfigClient.getBoolean(KEY)&&ConfigClient.getBoolean(BACKFILL)&&scanning.compareAndSet(false,true))worker.execute{
            try{(list.invoke(null) as? List<*>)?.filterNotNull()?.forEach(::record)}catch(e:Exception){HookRuntime.event(FEATURE,"已下载列表补缓存失败 type="+e.javaClass.simpleName)}
            finally{scanning.set(false)}
            main.post{pump()}
        }}
        fun retryPending(){worker.execute{
            val edit=prefs(app).edit()
            for((key,value)in prefs(app).all)try{
                val job=JSONObject(value as String)
                if(job.optString("state")!="complete"){job.put("attempts",0).put("retryAt",0L);edit.putString(key,job.toString())}
            }catch(_:Exception){}
            edit.commit();main.post{pump()}
        }}
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var validated:Network?=null
        val callback=object:ConnectivityManager.NetworkCallback(){
            override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){
                if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&validated!=network){validated=network;main.post{retryPending();backfill()}}
                else if(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&validated==network)validated=null
            }
            override fun onLost(network:Network){if(validated==network)validated=null}
        }
        try{cm.registerDefaultNetworkCallback(callback)}catch(_:Exception){}
        HookRuntime.onDispose(FEATURE){closedFlag.set(true);enqueueVisible=null;worker.shutdownNow();try{cm.unregisterNetworkCallback(callback)}catch(_:Exception){}}
        worker.execute{
            readyFlag.set(OfflineCommentDownloadContract.verify(app))
            main.post{if(ready()){backfill();pump()}else HookRuntime.state(FEATURE,"下载回调代码不匹配，未自动联网")}
        }
        var signature:List<Boolean>?=null
        HookRuntime.subscribe(FEATURE){snapshot->if(snapshot!=null){
            val next=listOf(snapshot.boolean(KEY),snapshot.boolean(BACKFILL),snapshot.boolean(PASSIVE))
            if(next!=signature){signature=next;if(snapshot.boolean(KEY)){retryPending();backfill();pump()}}
            HookRuntime.appliedConfiguration(FEATURE,snapshot)
        }}
    }
}
