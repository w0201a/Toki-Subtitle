package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import java.lang.ref.WeakReference

/** Owns exactly one page-mounted presenter. Native renderers and timeline callbacks only submit input. */
internal class SubtitleSessionPresenter(private val translator:NativeSubtitleTranslator?,
    private val configuration: () -> io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot? = {
        if (io.github.meiyongai.toki.subtitle.provider.ConfigClient.isReady) io.github.meiyongai.toki.subtitle.provider.ConfigClient.snapshot() else null
    }):AutoCloseable {
    private val controller=SubtitleDisplayController()
    private var anchor=WeakReference<View>(null)
    private var video=""
    private var overlay:SubtitleOverlay?=null
    private val nativeViews=java.util.WeakHashMap<View,String>()
    private data class NativeFrame(val text:String,val visible:Boolean,val sequence:Long)
    private val nativeFrames=java.util.WeakHashMap<View,NativeFrame>()
    private var nativeOwner=WeakReference<View>(null)
    private var sequence=0L
    private var diagnostic=""
    fun select(view:View?,id:String) {
        if(id.isBlank())return
        val changed=video!=id
        if(changed){overlay?.close();overlay=null;nativeViews.clear();nativeFrames.clear();nativeOwner.clear();video=id;anchor=WeakReference(view)}
        else if(view!=null&&anchor.get()!==view){
            val existing=anchor.get()
            if(existing==null||!existing.isAttachedToWindow||!existing.getGlobalVisibleRect(android.graphics.Rect())||existing.rootView!==view.rootView){
                overlay?.close();overlay=null;anchor=WeakReference(view)
            }
        }
        val identityChanged=controller.select(id,translator?.playbackRevision?:0,translator?.trackIdentity(id).orEmpty())
        if(identityChanged){nativeFrames.clear();nativeOwner.clear()}
        if(changed||identityChanged)render()
    }
    fun native(root:View,id:String,state:String,text:String) {
        if(id.isBlank())return
        if(video.isBlank())select(root,id)
        if(id!=video){reportInput("other-video",state,text.length,root);return}
        if(!root.isAttachedToWindow){
            if(nativeOwner.get()===root){nativeFrames.remove(root);nativeOwner.clear();syncNativeInput();render()}
            reportInput("detached",state,text.length,root);return
        }
        nativeViews[root]=id
        if(controller.select(id,translator?.playbackRevision?:0,translator?.trackIdentity(id).orEmpty())){nativeFrames.clear();nativeOwner.clear()}
        val visible=text.isNotBlank()&&(state=="EXPANDED" || state=="HIDDEN"&&matchingVisibleText(root,text))
        nativeFrames[root]=NativeFrame(text,visible,++sequence)
        val owner=nativeOwner.get()
        if(visible)nativeOwner=WeakReference(root)
        if(!visible&&owner===root)nativeOwner.clear() // Never resurrect an older renderer's cached sentence.
        reportInput(if(visible)"accepted-visible"else"accepted-hidden",state,text.length,root)
        syncNativeInput()
        render()
    }
    private var lastInput=""
    private fun reportInput(reason:String,state:String,chars:Int,root:View){
        val detail="reason=$reason state=$state chars=$chars attached=${root.isAttachedToWindow} shown=${root.isShown} size=${root.width}x${root.height}"
        if(detail!=lastInput){lastInput=detail;HookRuntime.event("SubtitleInput",detail)}
    }
    private fun syncNativeInput(){
        val root=nativeOwner.get()
        val live=root?.takeIf{it.isAttachedToWindow&&it.rootView===anchor.get()?.rootView}
        // Renderer geometry is not playback visibility: clean/custom mode can collapse the native container.
        // The single overlay independently verifies the selected player page before displaying anything.
        if(live==null)nativeOwner.clear()
        val selected=live?.let{nativeFrames[it]}
        controller.native(video,translator?.playbackRevision?:0,selected?.text.orEmpty(),selected?.visible==true)
    }
    fun nativeRevision(root:View):Long=nativeFrames[root]?.sequence?:0L
    private fun matchingVisibleText(root:View,text:String):Boolean {
        if(text.isBlank())return false
        val pending=ArrayDeque<View>();pending.add(root);var inspected=0;var count=0
        while(pending.isNotEmpty()&&inspected++<32){val view=pending.removeFirst()
            if(view is android.widget.TextView&&view.text.toString()==text&&view.isShown)count++
            if(view is android.view.ViewGroup)for(i in 0 until minOf(view.childCount,32-inspected))pending.add(view.getChildAt(i))
        };return count==1
    }
    fun progress(view:View?,id:String,position:Long) {
        select(view,id)
        syncNativeInput()
        val ready=translator?.timelineReady(id)==true
        val cue=if(ready)translator?.cueRangeAt(id,position)else null
        controller.progress(id,translator?.playbackRevision?:0,ready,cue)
        val frame=controller.frame()
        val detail="source=${frame.source} pass=${frame.pass} track=${frame.track.take(8)} ready=$ready range=${frame.startMs}:${frame.endMs} blank=${frame.text.isBlank()} nativeOwners=${nativeFrames.size}"
        if(detail!=diagnostic){diagnostic=detail;HookRuntime.event("SubtitleSession",detail+" positionMs=$position")}
        render()
    }
    private fun render() {
        val view=anchor.get()?:return
        val frame=controller.frame()
        val binding=overlay?:SubtitleOverlay(view,translator,timelineSource=true,singleOwner=true,
            nativeSources={nativeViews.keys.filter{nativeViews[it]==video}},
            authoritativeGap={controller.frame().source=="timeline"&&controller.frame().text.isBlank()},configuration=configuration).also{overlay=it}
        binding.update(if(frame.text.isBlank())"HIDDEN"else"EXPANDED",frame.text,frame.video)
        // Every submitted frame is rendered on the host main thread; normal pre-draw also handles geometry.
        binding.onPreDraw()
    }
    fun hit(activity:android.app.Activity,x:Float,y:Float):SubtitleOverlay?=overlay?.takeIf{it.hit(activity,x,y)}
    override fun close(){overlay?.close();overlay=null;nativeViews.clear();nativeFrames.clear();nativeOwner.clear();video="";anchor.clear();controller.select("",-1,"")}
}
