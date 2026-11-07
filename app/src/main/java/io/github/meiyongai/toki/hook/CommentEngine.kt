package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Sole network writer for the new comment repository. Viewers submit commands; they never run workers. */
internal object CommentEngine {
    private const val OWNER="OfflineCommentDownloadHook"
    private val main=Handler(Looper.getMainLooper())
    private var engine: Runtime?=null
    fun install(module:XposedModule,loader:ClassLoader) {
        check(engine==null)
        val app=checkNotNull(HookRuntime.hostContext)
        if(android.app.Application.getProcessName()!=app.packageName) { HookRuntime.state(OWNER,"仅主进程拥有评论任务引擎");return }
        val runtime=Runtime(app,loader)
        engine=runtime
        val aweme=loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme")
        val completion=loader.loadClass("X.0rF7").getDeclaredMethod("LIZ",aweme,String::class.java).apply{isAccessible=true}
        module.trackHook(OWNER,completion,requiresConfiguration=false).intercept { chain ->
            val result=chain.proceed();chain.args[0]?.let(runtime::offer);result
        }
        module.deoptimize(completion)
        // A manual host clear is an epoch barrier, not permission to destroy companion data.
        val reason=loader.loadClass("X.0A9I")
        val manual=checkNotNull(reason.enumConstants).single{(it as Enum<*>).name=="CLICK_CLEAR_ICON"}
        val clear=loader.loadClass("X.0A9A").getDeclaredMethod("LJIIIZ",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,reason)
        module.trackHook(OWNER,clear,requiresConfiguration=false).intercept { chain ->
            if(chain.args[2]===manual)runtime.suspendAccount()
            chain.proceed()
        }
        HookRuntime.subscribe(OWNER) { settings -> if(settings!=null)runtime.configure() }
        HookRuntime.onDispose(OWNER) { runtime.close();engine=null }
        runtime.configure()
    }
    fun offer(model:Any) { engine?.offer(model) }
    fun statusSnapshot():Bundle?=engine?.status?.get()?.let(::Bundle)
    fun submitCommand(account:String,video:String,command:CommentRepository.Command) {
        val runtime=checkNotNull(engine){"Comment engine not loaded"}
        runtime.index.execute { runtime.repo.submitCommand(account,video,command);runtime.wake() }
    }
    fun registerForRead(model:Any,done:(Result<Pair<String,String>>)->Unit) {
        val runtime=engine
        if(runtime==null) { done(Result.failure(IllegalStateException("Comment engine unavailable")));return }
        runtime.readers.execute {
            val result=runCatching {
                val account=runtime.reader.account(runtime.app);val video=checkNotNull(runtime.id.invoke(model)).toString();val lang=runtime.target()
                runtime.repo.migrateLocalScope(account,onlyVideo=video)
                runtime.repo.migrateLegacy(account,lang,onlyVideo=video){OfflineCommentLimitOverrides.quotas(runtime.app,account,it)}
                check(runtime.reader.accountMatches(account)) { "Account changed during local read" }
                runtime.repo.register(account,video,runtime.reader.serializeModel(model),OfflineCommentLimitOverrides.quotas(runtime.app,account,video),lang)
                if(runtime.repo.readVideo(account,video)?.snapshot?.rows.isNullOrEmpty()) {
                    val native=runCatching { runtime.reader.read(runtime.app,video){!runtime.closed.get()&&runtime.reader.accountMatches(account)} }.getOrNull()
                    if(native!=null)runtime.repo.importPage(CommentTranslationStore.restore(runtime.app,native,lang))
                }
                check(runtime.reader.accountMatches(account));account to video
            }
            main.post { done(result) }
        }
    }
    private class Runtime(val app:Context,val loader:ClassLoader):AutoCloseable {
        val repo=CommentRepository.get(app)
        val reader=HostOfflineComments(loader)
        val id=loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme").getMethod("getAid")
        val network=HostCommentPageNetwork(loader);val replies=HostCommentReplyNetwork(loader)
        fun worker(name:String)=Executors.newSingleThreadExecutor{r->Thread(r,name).apply{isDaemon=true}}
        val index=worker("toki-comment-v3-index")
        val readers=worker("toki-comment-v3-reader")
        private val concurrency=CommentConcurrency(4)
        private val workers=CommentRepository.Lane.entries.associateWith{lane->
            Executors.newFixedThreadPool(concurrency.limit(lane)){task->Thread(task,"toki-comment-v3-${lane.name.lowercase()}").apply{isDaemon=true}}
        }
        val closed=AtomicBoolean(false)
        val status=AtomicReference<Bundle?>()
        private var signature=""
        private val verified=AtomicBoolean(false)
        private val tick=Runnable{wake()}
        private var wakeAt=Long.MAX_VALUE
        private val migratedAccounts=mutableSetOf<String>()
        private val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val connection=object:ConnectivityManager.NetworkCallback(){
            override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))main.post{wake()}}
        }
        init { try { cm.registerDefaultNetworkCallback(connection) } catch(_:Exception) {} }
        private val subscriptions=mutableListOf<AutoCloseable>()
        fun target():String=try {
            val facade=loader.adaptiveClass("X.03hV")
            val value=facade.adaptiveDeclaredMethod("LLILLIZIL").invoke(facade.adaptiveDeclaredField("LIZIZ").get(null)) as String
            CommentTranslationKey.normalizedTarget(value)?:"zh-hans"
        } catch(_:Exception){"zh-hans"}
        fun backgroundAllowed()=ConfigClient.isReady&&ConfigClient.getBoolean(OfflineCommentDownloadHook.KEY)
        fun connected():Boolean=try {
            val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps=cm.getNetworkCapabilities(cm.activeNetwork?:return false)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true&&TranslationNetworkPolicy.capture().permitsNetwork()
        }catch(_:Exception){false}
        fun register(model:Any) {
            val account=reader.account(app);val video=id.invoke(model) as String
            val language=target()
            repo.register(account,video,reader.serializeModel(model),OfflineCommentLimitOverrides.quotas(app,account,video),language)
        }
        fun offer(model:Any) {
            if(closed.get()||!backgroundAllowed())return
            index.execute { if(!closed.get()) { try { register(model) } catch(e:Exception){HookRuntime.event(OWNER,"registration ${e.javaClass.simpleName}")};wake() } }
        }
        fun configure() {
            if(closed.get())return
            index.execute {
                if(closed.get())return@execute
                try {
                    verified.set(OfflineCommentDownloadContract.verify(app))
                    val a=reader.account(app);val language=target()
                    if(migratedAccounts.add(a))try { repo.migrateLocalScope(a);repo.migrateLegacy(a,language){OfflineCommentLimitOverrides.quotas(app,a,it)} }
                    catch(error:Exception){migratedAccounts.remove(a);throw error}
                    val next=listOf(backgroundAllowed(),ConfigClient.getString(OfflineCommentPreferences.LIMIT),ConfigClient.getString(OfflineCommentPreferences.REPLY_LIMIT),ConfigClient.getString(OfflineCommentPreferences.THREAD_LIMIT),language).toString()
                    if(next!=signature) {
                        signature=next
                        repo.videos(a).forEach { v->repo.register(a,v,"",OfflineCommentLimitOverrides.quotas(app,a,v),language) }
                    }
                    if(backgroundAllowed()&&ConfigClient.getBoolean(OfflineCommentDownloadHook.BACKFILL)) {
                        val method=loader.loadClass("X.0A6x").getDeclaredMethod("LJIJI").apply{isAccessible=true}
                        (method.invoke(null) as? List<*>)?.filterNotNull()?.forEach { model->register(model) }
                    }
                }catch(e:Exception){HookRuntime.event(OWNER,"configure ${e.javaClass.simpleName}")}
                publish();wake()
            }
        }
        fun suspendAccount() { try { repo.suspendAccount(reader.account(app)) }catch(_:Exception){} }
        fun wake() {
            if(Looper.myLooper()!=main.looper){main.post{wake()};return}
            if(closed.get())return
            main.removeCallbacks(tick);wakeAt=Long.MAX_VALUE
            if(verified.get()&&connected())CommentRepository.Lane.entries.forEach(::launch)
        }
        private fun scheduleWake(delay:Long){main.post {
            if(!closed.get()){
                val at=android.os.SystemClock.uptimeMillis()+delay
                if(at<wakeAt){wakeAt=at;main.removeCallbacks(tick);main.postAtTime(tick,at)}
            }
        }}
        private fun launch(lane:CommentRepository.Lane) {
            repeat(concurrency.limit(lane)){launchOne(lane)}
        }
        private fun launchOne(lane:CommentRepository.Lane) {
            if(!concurrency.acquire(lane))return
            try { workers.getValue(lane).execute {
                try {
                    val a=reader.account(app)
                    repeat(5) {
                        if(closed.get()||!connected())return@repeat
                        val w=repo.claim(a,lane,System.currentTimeMillis(),backgroundAllowed())?:return@repeat
                        try {
                            check(active(w))
                            when(lane) {
                                CommentRepository.Lane.SOURCE->source(w)
                                CommentRepository.Lane.TRANSLATION->translate(w)
                                CommentRepository.Lane.MEDIA->{OfflineCommentMediaStore.cache(app,w.account,w.video,checkNotNull(w.asset)){active(w)};check(repo.commitMedia(w))}
                            }
                        }catch(e:Exception){repo.fail(w,e,System.currentTimeMillis())}
                    }
                }catch(e:Exception){HookRuntime.event(OWNER,"lane=${lane.name} ${e.javaClass.simpleName}")}
                finally {
                    concurrency.release(lane)
                    if(!closed.get())try {
                        index.execute{publish()}
                        repo.nextDelay(reader.account(app),lane,System.currentTimeMillis(),backgroundAllowed())?.let(::scheduleWake)
                    }catch(_:java.util.concurrent.RejectedExecutionException){}
                }
            } } catch(error:java.util.concurrent.RejectedExecutionException){concurrency.release(lane);if(!closed.get())throw error}
        }
        private fun active(w:CommentRepository.Work)=!closed.get()&&connected()&&(backgroundAllowed()||w.manualAuthorized)&&reader.accountMatches(w.account)&&repo.leaseAlive(w)
        private fun <T> await(start:((Result<T>)->Unit)->AutoCloseable):T {
            val latch=CountDownLatch(1);val result=AtomicReference<Result<T>?>();val once=AtomicBoolean(false)
            val handle=start{value->if(once.compareAndSet(false,true)){result.set(value);latch.countDown()}}
            try { if(!latch.await(25,TimeUnit.SECONDS))throw TimeoutException("Comment operation timeout");return checkNotNull(result.get()).getOrThrow() }
            finally{handle.close()}
        }
        private fun source(w:CommentRepository.Work) {
            val model=reader.parseModel(w.model)
            val response=await<Any>{done->val op=CommentPageOperation();val remote=LocalOnlyRequest{active(w)}
                if(w.item.isBlank())network.fetch(app,reader,w.video,model,w.cursor,w.account,remote,op,done)
                else replies.fetch(app,reader,w.video,model,w.item,w.cursor,w.account,remote,op,done)
                op
            }
            val page=reader.copyResponse(app,w.video,w.account,response,System.currentTimeMillis(),w.item.takeIf{it.isNotBlank()}){active(w)}
            check(active(w)&&repo.commitPage(w,page))
        }
        private fun translate(w:CommentRepository.Work) {
            val r=checkNotNull(w.row);require(!r.sourceTruncated)
            val snapshot=OfflineCommentData.Snapshot(w.account,w.video,0,listOf(r),1,false,false)
            val restored=CommentTranslationStore.restore(app,snapshot,w.target).rows.firstOrNull()?.translation
            val value=restored?:await<String>{done->
                val cancelled=AtomicBoolean(false);var task:ManualCommentTranslation?=null
                main.post {
                    if(cancelled.get()||!active(w)){done(Result.failure(IllegalStateException("Cancelled")));return@post}
                    val failure=AtomicReference<Throwable?>()
                    val started=android.os.SystemClock.uptimeMillis()
                    val transport=NativeSubtitleTranslator.transport(loader,access={LocalOnlyRequest{active(w)}},failure={failure.set(it)})
                    task=ManualCommentTranslation(translate=transport,timeoutMs=20_000,post={action,delay->main.postDelayed(action,delay);Unit},remove={main.removeCallbacks(it)},changed={s->
                        if(!s.busy) { val text=s.translated?:s.original.takeIf{s.failed==0&&s.completed==s.total}
                            done(if(text!=null&&s.failed==0)Result.success(text)else Result.failure(failure.get()?:if(android.os.SystemClock.uptimeMillis()-started>=20_000)TimeoutException("Comment translation timed out")else IllegalStateException("Translation response empty"))) }
                    });task?.start(r.source)
                }
                AutoCloseable { cancelled.set(true);main.post{task?.close()} }
            }
            check(active(w)&&repo.commitTranslation(w,value))
        }
        private fun publish() {
            if(closed.get())return
            try {
                val counts=repo.progressCounts(reader.account(app))
                status.set(Bundle().apply {
                    putInt("batchSize",5)
                    putInt("translationParallelism",concurrency.limit(CommentRepository.Lane.TRANSLATION))
                    putInt("translationInFlight",concurrency.active(CommentRepository.Lane.TRANSLATION))
                    counts.forEach{(key,value)->putInt(key,value)}
                    putInt("preparedPartial",counts.getValue("partial"))
                    putBoolean("network",connected()&&backgroundAllowed());putBoolean("verified",verified.get())
                    putString("failure",if(counts.getValue("retrying")>0)"item retry pending; see video details" else "")
                    putLong("updated",System.currentTimeMillis());putString("storage","sqlite-v3")
                    putInt("commentLimit",OfflineCommentPreferences.limit(ConfigClient.getString(OfflineCommentPreferences.LIMIT)))
                })
            }catch(_:Exception){}
        }
        override fun close() {
            closed.set(true);main.removeCallbacks(tick);subscriptions.forEach{it.close()}
            try { cm.unregisterNetworkCallback(connection) } catch(_:Exception){}
            workers.values.forEach{it.shutdownNow()};readers.shutdownNow();index.shutdownNow()
            // Shared repository remains readable by a still-open cache-only viewer.
        }
    }
}
