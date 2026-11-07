package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="zh-rCN-w412dp-h915dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommentReaderSheetTest {
    @org.junit.Before fun runtimeReady(){HookRuntime.start("comment-reader-regression"){_,_->}}
    private fun row(id:String,parent:String="",text:String="A comment worth reading",translation:String?="一条值得阅读的评论")=
        OfflineCommentData.Row(id,"Traveler","traveler","user",text,translation,"zh-hans",if(parent.isBlank())0 else 1,3,parentId=parent,mediaKnown=true)
    private class Fake(var state:CommentRepository.VideoState):CommentReaderSheet.Source {
        var observer:(()->Unit)?=null;var matches=true;var deferRegistration=false;var registration:((Result<Pair<String,String>>)->Unit)?=null;val commands=mutableListOf<CommentRepository.Command>()
        override fun register(model:Any,done:(Result<Pair<String,String>>)->Unit){if(deferRegistration)registration=done else done(Result.success("a".repeat(64) to "video"))}
        override fun observeVideo(account:String,video:String,changed:()->Unit):AutoCloseable{observer=changed;return AutoCloseable{observer=null}}
        override fun readVideo(account:String,video:String)=state
        override fun accountMatches(account:String)=matches
        override fun submitCommand(account:String,video:String,command:CommentRepository.Command){commands+=command}
    }
    private fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))} else emptyList())
    private fun settle(until:()->Boolean) { repeat(60){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25));if(until())return;Thread.sleep(25)};assertTrue("reader update timed out",until()) }
    @Test fun realSheetHasReplyPreviewsFeedbackAndCacheOnlyModeChanges() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();val activity=owner.get()
        val rows=listOf(row("a"),row("r1","a",text="First reply",translation="第一条回复"),row("r2","a",text="Second reply",translation="第二条回复"),row("r3","a"),row("missing",text="Me grabbing my child",translation=null))
        val snapshot=OfflineCommentData.Snapshot("a".repeat(64),"video",1,rows,2512,true,false)
        val fake=Fake(CommentRepository.VideoState(snapshot,2,3,4,5,0,0,"queued","fetching",false,false,mapOf("missing" to CommentRepository.ItemState("pending","",0)),emptyMap(),0))
        val dialog=CommentReaderSheet.show(activity,"video",HostOfflineComments(javaClass.classLoader!!),Any(),{true},{},fake)
        val panel=dialog.window!!.decorView.findViewWithTag<View>("v3-comment-sheet")
        fun layout(){panel.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(659,View.MeasureSpec.EXACTLY));panel.layout(0,0,412,659)}
        val list=descendants(panel).filterIsInstance<ListView>().single()
        settle{list.adapter.count==4};layout()
        assertTrue("Native auto-advance must see the open reader",CommentReaderSheet.isReading);assertTrue(list.height>350);assertTrue(list.adapter.hasStableIds());assertEquals(4,list.adapter.count)
        val preview=list.adapter.getView(0,null,list)
        val expand=descendants(preview).filterIsInstance<Button>().first{it.text.toString().startsWith("展开其余")}
        expand.performClick();layout();assertEquals(5,list.adapter.count)
        val modes=descendants(panel).filterIsInstance<Button>().filter{it.text.toString() in listOf("原文","翻译","双语")}
        modes.forEach{it.performClick()};assertEquals(0,fake.commands.size)
        val missing=list.adapter.getView(4,null,list)
        assertTrue(descendants(missing).filterIsInstance<TextView>().any{it.text.toString().contains("待翻译")})
        val beforeId=list.adapter.getItemId(0)
        fake.state=fake.state.copy(translated=5,snapshot=snapshot.copy(rows=rows.map{if(it.id=="missing")it.copy(translation="我抱起我的孩子")else it}))
        fake.observer!!();settle{panel.findViewWithTag<TextView>("v3-comment-progress").text.contains("5/5")};layout()
        assertEquals(5,list.adapter.count);assertEquals(beforeId,list.adapter.getItemId(0));assertEquals(0,fake.commands.size)
        val dir=File("work/rebuild-core-20261106/gui").apply{mkdirs()}
        val image=Bitmap.createBitmap(412,659,Bitmap.Config.ARGB_8888);panel.draw(Canvas(image));File(dir,"comments-v3.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        descendants(panel).filterIsInstance<Button>().first{it.text=="操作"}.performClick()
        val actions=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();shadowOf(actions).clickOnItem(2)
        assertEquals(listOf(CommentRepository.Command.Pause),fake.commands)
        dialog.dismiss();shadowOf(Looper.getMainLooper()).idle();assertFalse(CommentReaderSheet.isReading);assertNull(fake.observer);owner.pause().stop().destroy()
    }
    @Test fun delayedLocalInitializationAndLoginChangeDoNotCloseTheLocalReader() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();val activity=owner.get()
        val snapshot=OfflineCommentData.Snapshot("a".repeat(64),"video",1,listOf(row("saved")),1,false,false)
        val fake=Fake(CommentRepository.VideoState(snapshot,1,0,1,1,0,0,"ready","server_ended",false,false,emptyMap(),emptyMap(),0)).apply{deferRegistration=true}
        var selected=true
        val dialog=CommentReaderSheet.show(activity,"video",HostOfflineComments(javaClass.classLoader!!),Any(),{selected},{},fake)
        shadowOf(Looper.getMainLooper()).idle();selected=false
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertTrue("A replaced/resumed playback holder must not dismiss the pinned local reader",dialog.isShowing)
        fake.registration!!(Result.success("a".repeat(64) to "video"))
        val panel=dialog.window!!.decorView.findViewWithTag<View>("v3-comment-sheet")
        val list=descendants(panel).filterIsInstance<ListView>().single()
        settle{list.adapter.count==1}
        assertTrue(dialog.isShowing);assertEquals(0,fake.commands.size)
        fake.matches=false;shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100))
        assertTrue("Login account changes do not own local cached content",dialog.isShowing)
        assertNotNull(fake.observer)
        dialog.dismiss();shadowOf(Looper.getMainLooper()).idle();assertFalse(CommentReaderSheet.isReading);assertNull(fake.observer);owner.pause().stop().destroy()
    }

    @Test fun coverageDetailsExplainActualDeficitsInsteadOfShowingAnOpaqueDebugCode() {
        val explanation=CommentReaderSheet.coverageExplanation("root_shortfall:79/333;no_growth_three_pages;truncated_bodies:2;reply_shortfall")
        assertTrue(explanation.contains("79/333"));assertTrue(explanation.contains("连续三页"))
        assertTrue(explanation.contains("2 条正文被截断"));assertTrue(explanation.contains("回复未达"))
        assertFalse(explanation.contains("root_shortfall"))
        assertEquals("已达到当前设置上限",CommentReaderSheet.coverageExplanation("quota"))
    }

    @Test fun tappingOutsideTheReaderCancelsOnlyTheWindowAndReleasesReadingGuard() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();val activity=owner.get()
        val snapshot=OfflineCommentData.Snapshot("a".repeat(64),"video",1,listOf(row("a")),1,false,false)
        val fake=Fake(CommentRepository.VideoState(snapshot,1,0,1,1,0,0,"ready","quota",false,false,emptyMap(),emptyMap(),0))
        var dismissed=0
        val dialog=CommentReaderSheet.show(activity,"video",HostOfflineComments(javaClass.classLoader!!),Any(),{true},{dismissed++},fake)
        val panel=dialog.window!!.decorView.findViewWithTag<View>("v3-comment-sheet")
        settle{fake.observer!=null}
        activity.window.decorView.apply{measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));layout(0,0,1080,2400)}
        dialog.window!!.decorView.apply{measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));layout(0,0,1080,1600)}
        assertTrue(dialog.isShowing);assertTrue(CommentReaderSheet.isReading)
        val now=android.os.SystemClock.uptimeMillis()
        val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_OUTSIDE,-50f,-50f,0)
        assertTrue(dialog.onTouchEvent(event));event.recycle();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing);assertFalse(CommentReaderSheet.isReading);assertEquals(1,dismissed)
        assertNull(fake.observer);assertTrue(fake.commands.isEmpty())
        owner.pause().stop().destroy()
    }

}
