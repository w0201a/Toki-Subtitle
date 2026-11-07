package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native cache-only sheet. All mutations are commands to the single engine, never requests from the adapter. */
internal object CommentReaderSheet {
    private val openReaders=java.util.concurrent.atomic.AtomicInteger()
    val isReading:Boolean get()=openReaders.get()>0
    internal fun coverageExplanation(reason:String):String = reason.split(';').filter{it.isNotBlank()}.joinToString("；") { token ->
        val kind=token.substringBefore(':');val value=token.substringAfter(':',"")
        when(kind) {
            "root_shortfall" -> "主评论未达设置：$value"
            "truncated_bodies" -> "$value 条正文被截断，未当作完整内容"
            "unknown_media" -> "$value 条评论的媒体描述未核验"
            "migration_errors" -> "$value 页旧缓存导入异常，原文件保留"
            "no_growth_three_pages" -> "连续三页无新增唯一评论"
            "cursor_not_advancing" -> "接口返回的下一页游标未前进"
            "response_incomplete" -> "接口结束但响应内容未完整保存"
            "reply_shortfall" -> "接口已结束，回复未达当前串要求"
            "quota" -> "已达到当前设置上限"
            "server_ended" -> "接口已结束"
            "fetching" -> "仍有抓取任务"
            else -> "旧抓取流覆盖不足，原记录保留，可明确重试"
        }
    }
    internal interface Source {
        fun register(model:Any,done:(Result<Pair<String,String>>)->Unit)
        fun observeVideo(account:String,video:String,changed:()->Unit):AutoCloseable
        fun readVideo(account:String,video:String):CommentRepository.VideoState?
        fun accountMatches(account:String):Boolean
        fun submitCommand(account:String,video:String,command:CommentRepository.Command)
    }
    internal fun visibleRows(rows:List<OfflineCommentData.Row>,expanded:Set<String>,rootLimit:Int):List<OfflineCommentData.Row> {
        val byId=rows.associateBy{it.id}
        fun rootOf(r:OfflineCommentData.Row):String? { var p=r;var n=0;while(p.parentId.isNotBlank()&&n++<4)p=byId[p.parentId]?:return null;return p.id }
        val groups=rows.filter{it.parentId.isNotBlank()}.groupBy{rootOf(it)}
        return rows.filter{it.parentId.isBlank()}.take(rootLimit).flatMap { r ->listOf(r)+groups[r.id].orEmpty().let{if(r.id in expanded)it else it.take(2)} }
    }
    fun show(activity:Activity,video:String,reader:HostOfflineComments,model:Any,current:()->Boolean,dismissed:(AlertDialog)->Unit,
        suppliedSource:Source?=null):AlertDialog {
        val context=TokiDialogs.builder(if(suppliedSource==null)HostSettingsEntry.localizedContext(activity)else activity).context
        val app=activity.applicationContext;val main=Handler(Looper.getMainLooper());val closed=AtomicBoolean(false)
        val worker=Executors.newSingleThreadExecutor{r->Thread(r,"toki-comment-reader").apply{isDaemon=true}}
        val source=suppliedSource?:object:Source {
            private val repo=CommentRepository.get(app)
            override fun register(model:Any,done:(Result<Pair<String,String>>)->Unit)=CommentEngine.registerForRead(model,done)
            override fun observeVideo(account:String,video:String,changed:()->Unit)=repo.observeVideo(account,video,changed)
            override fun readVideo(account:String,video:String)=repo.readVideo(account,video)
            override fun accountMatches(account:String)=reader.accountMatches(account)
            override fun submitCommand(account:String,video:String,command:CommentRepository.Command)=CommentEngine.submitCommand(account,video,command)
        }
        var account="";var state:CommentRepository.VideoState?=null;var subscription:AutoCloseable?=null
        var reading=false;var dirty=false;var shown=40
        val expanded=mutableSetOf<String>()
        var mode=when(ConfigClient.getString(OfflineCommentPreferences.MODE,"translated")){"original"->OfflineCommentData.Mode.ORIGINAL;"bilingual"->OfflineCommentData.Mode.BILINGUAL;else->OfflineCommentData.Mode.TRANSLATED}
        fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
        val ink=Color.rgb(242,242,244);val secondary=Color.rgb(174,174,182);val accent=Color.rgb(91,214,205)
        fun label(size:Float,color:Int=ink)=TextView(context).apply{textSize=size;setTextColor(color)}
        fun button(text:String)=Button(context).apply{this.text=text;textSize=13f;isAllCaps=false;setTextColor(ink);minimumHeight=dp(48);minHeight=dp(48);setPadding(dp(10),0,dp(10),0);background=android.graphics.drawable.StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_pressed),GradientDrawable().apply{setColor(0x33333333);cornerRadius=dp(10).toFloat()})
            addState(intArrayOf(),android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))}}
        val root=LinearLayout(context).apply{tag="v3-comment-sheet";orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(6),dp(14),dp(8));background=GradientDrawable().apply{setColor(Color.rgb(29,29,31));cornerRadii=floatArrayOf(dp(18).toFloat(),dp(18).toFloat(),dp(18).toFloat(),dp(18).toFloat(),0f,0f,0f,0f)}}
        val grip=FrameLayout(context).apply{addView(View(context).apply{background=GradientDrawable().apply{setColor(secondary);cornerRadius=dp(2).toFloat()}},FrameLayout.LayoutParams(dp(34),dp(4),Gravity.CENTER))}
        root.addView(grip,LinearLayout.LayoutParams(-1,dp(24)))
        val title=label(18f).apply{text="已缓存评论";typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
        val tools=button("操作");val close=button("×").apply{contentDescription="关闭评论"}
        root.addView(LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;addView(title,LinearLayout.LayoutParams(0,-2,1f));addView(tools,LinearLayout.LayoutParams(dp(56),dp(48)));addView(close,LinearLayout.LayoutParams(dp(48),dp(48)))})
        val summary=label(12f,secondary).apply{tag="v3-comment-progress";text="正在读取本地缓存…";setPadding(0,dp(2),0,dp(8))};root.addView(summary)
        val tabs=LinearLayout(context).apply{gravity=Gravity.CENTER;setPadding(0,0,0,dp(5))};root.addView(tabs)
        val feedback=label(12f,accent).apply{visibility=View.GONE;setPadding(0,0,0,dp(5));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(feedback)
        val list=ListView(context).apply{divider=null;dividerHeight=0;clipToPadding=false;isVerticalScrollBarEnabled=false}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val footer=label(12f,secondary).apply{gravity=Gravity.CENTER;setPadding(0,dp(5),0,dp(5))};root.addView(footer)
        val dialog=TokiDialogs.builder(context).setView(root).create()
        var readerWindowRegistered=false
        dialog.setCanceledOnTouchOutside(true)
        fun valid()=!closed.get()&&!activity.isFinishing&&!activity.isDestroyed
        fun notify(text:String){feedback.text=text;feedback.visibility=View.VISIBLE}
        fun submit(command:CommentRepository.Command) {
            if(!valid()||account.isBlank())return
            try{source.submitCommand(account,video,command);notify(if(command==CommentRepository.Command.Pause)"已提交暂停此视频" else "已加入公平队列，不插队")}
            catch(e:Exception){notify("无法提交：${e.message}")}
        }
        fun explicit(command:CommentRepository.Command) {
            if(!TranslationNetworkPolicy.capture().permitsNetwork()){notify("当前网络策略不允许联网，保留本地内容");return}
            TokiDialogs.builder(context).setTitle("联网补齐缓存").setMessage("将使用网络和现有翻译服务，按全部视频轮流处理；不会只处理当前可见评论。")
                .setPositiveButton("加入队列"){_,_->submit(command)}.setNegativeButton("取消",null).show()
        }
        lateinit var adapter:BaseAdapter
        var display=emptyList<OfflineCommentData.Row>()
        val stableIds=mutableMapOf<String,Long>();var nextId=0L
        fun project(){display=visibleRows(state?.snapshot?.rows.orEmpty(),expanded,shown)}
        fun rebuild(){val first=list.firstVisiblePosition;val key=display.getOrNull(first)?.id;val top=list.getChildAt(0)?.top?:0;project();adapter.notifyDataSetChanged();key?.let{k->val index=display.indexOfFirst{it.id==k};if(index>=0)list.setSelectionFromTop(index,top)}}
        class ThreadLayout:LinearLayout(context){
                var replyRail=false
                private val paint=android.graphics.Paint().apply{color=Color.rgb(65,65,72);strokeWidth=dp(1).toFloat()}
                init{setWillNotDraw(false);orientation=VERTICAL;setPadding(dp(4),dp(9),dp(2),dp(9))}
                override fun onDraw(canvas:android.graphics.Canvas){super.onDraw(canvas);if(replyRail)canvas.drawLine(dp(13).toFloat(),dp(6).toFloat(),dp(13).toFloat(),(height-dp(6)).toFloat(),paint)}
        }
        class Cell {
            val outer=ThreadLayout()
            val avatar=label(12f,secondary).apply{gravity=Gravity.CENTER;background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.rgb(49,49,54))}}
            val author=label(13f,secondary);val text=label(15f);val note=label(12f,secondary)
            val media=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
            val replies=button("").apply{setTextColor(secondary);gravity=Gravity.START}
            init{outer.addView(LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;addView(avatar,LinearLayout.LayoutParams(dp(28),dp(28)).apply{marginEnd=dp(8)});addView(author,LinearLayout.LayoutParams(0,-2,1f))});outer.addView(text,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)});outer.addView(media,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)});outer.addView(note);outer.addView(replies)}
        }
        adapter=object:BaseAdapter(){
            override fun getCount()=display.size
            override fun getItem(position:Int)=display[position]
            override fun getItemId(position:Int)=stableIds.getOrPut(display[position].id){++nextId}
            override fun hasStableIds()=true
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                val r=display[position];val s=state?:return convertView?:View(context)
                val cell=(convertView?.tag as? Cell)?:Cell().also{it.outer.tag=it}
                val reply=r.parentId.isNotBlank()
                cell.outer.setPadding(if(reply)dp(28)else dp(4),dp(9),dp(2),dp(9))
                cell.outer.replyRail=reply
                cell.outer.invalidate()
                cell.avatar.layoutParams=cell.avatar.layoutParams.apply{width=dp(if(reply)22 else 28);height=width}
                val name=r.handle.ifBlank{r.author}.ifBlank{"用户"}
                cell.avatar.text=name.take(1).uppercase()
                val parentRow=s.snapshot.rows.firstOrNull{it.id==r.parentId}
                cell.author.text=if(reply)"$name  ·  回复 @${parentRow?.handle?.ifBlank{parentRow.author}?:"主评论"}" else name
                cell.text.text=r.text(mode);cell.text.visibility=if(r.source.isBlank())View.GONE else View.VISIBLE
                val item=s.translationStates[r.id]
                cell.note.text=when{
                    r.sourceTruncated->"正文不完整，待修复"
                    !CommentRepository.needsTranslation(r)||mode==OfflineCommentData.Mode.ORIGINAL||!r.translation.isNullOrBlank()->""
                    item?.status=="running"->"翻译中"
                    item?.status=="retry"->"翻译失败，等待重试 · ${item.detail}"
                    else->"待翻译 · 原文保留"
                }
                cell.note.visibility=if(cell.note.text.isBlank())View.GONE else View.VISIBLE
                val owner=r.id;val source=r.source
                OfflineCommentMediaViews.bind(cell.media,context,s.snapshot,r,model.javaClass.classLoader?:context.classLoader,{valid()&&state?.snapshot?.rows?.any{it.id==owner&&it.source==source}==true},
                    status={asset->when(val m=s.mediaStates[asset.id]){null->if(s.mediaReady==s.mediaTotal)""else"媒体待下载";else->when(m.status){"running"->"媒体下载中";"retry"->"下载失败，等待重试";else->"媒体待下载"}}})
                val thread=if(reply)emptyList()else s.snapshot.rows.filter{it.parentId==r.id || (it.parentId.isNotBlank()&&it.parentId in s.snapshot.rows.filter{p->p.parentId==r.id}.map{p->p.id})}
                cell.replies.visibility=if(!reply&&thread.size>2)View.VISIBLE else View.GONE
                cell.replies.text=if(r.id in expanded)"收起回复 · 已缓存 ${thread.size} 条" else "展开其余 ${thread.size-2} 条已缓存回复"
                cell.replies.setOnClickListener{if(!expanded.add(r.id))expanded.remove(r.id);rebuild()}
                cell.outer.setOnLongClickListener{
                    TokiDialogs.builder(context).setItems(arrayOf("在线翻译此评论（排队）","复制原文")){_,which->if(which==0)explicit(CommentRepository.Command.Translate(r.id))else{
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("comment",r.source));notify("已复制原文")}}
                        .show();true
                }
                return cell.outer
            }
        };list.adapter=adapter
        val modeButtons=mutableMapOf<OfflineCommentData.Mode,Button>()
        for((value,name)in listOf(OfflineCommentData.Mode.ORIGINAL to "原文",OfflineCommentData.Mode.TRANSLATED to "翻译",OfflineCommentData.Mode.BILINGUAL to "双语")){
            val b=button(name);modeButtons[value]=b;tabs.addView(b,LinearLayout.LayoutParams(0,dp(48),1f));b.setOnClickListener{mode=value;modeButtons.forEach{(m,view)->view.setTextColor(if(m==mode)accent else secondary)};adapter.notifyDataSetChanged()}
        };modeButtons.forEach{(m,b)->b.setTextColor(if(m==mode)accent else secondary)}
        fun render(s:CommentRepository.VideoState){state=s;rebuild()
            val phase=when(s.phase){"ready"->"已准备好";"partial"->"部分完成";"running"->"处理中";"retry"->"等待重试";"paused"->"已暂停";else->"排队中"}
            val roots=s.snapshot.rows.count{it.parentId.isBlank()};val replies=s.snapshot.rows.size-roots
            val limits=OfflineCommentLimitOverrides.quotas(app,account,video)
            summary.text="主评论 $roots/${limits.comments} · 回复 $replies/${limits.replies} · 每串 ${limits.perThread}\n译文 ${s.translated}/${s.translatable} · 媒体 ${s.mediaReady}/${s.mediaTotal} · $phase"
            footer.text=when{shown<roots->"继续下滑，读取更多本地评论";s.phase=="ready"->"已显示当前范围内的本地缓存";s.phase=="partial"->"本地已读完 · ${coverageExplanation(s.reason)}";else->"本地已读完 · 后台进度更新后自动显示"}
        }
        lateinit var refresh:()->Unit
        refresh={if(valid()&&account.isNotBlank()){dirty=true;if(!reading){reading=true;dirty=false;worker.execute{val result=runCatching{source.readVideo(account,video)};main.post{
            reading=false;if(valid()){result.onSuccess{value->if(value!=null)render(value)else{summary.text="此视频尚无本地评论";footer.text="可在操作中选择联网补齐；本次读取未联网"}}.onFailure{notify("读取失败：${it.javaClass.simpleName}")};if(dirty)refresh()}
        }}}}}
        val refreshTick=Runnable{refresh()}
        list.setOnScrollListener(object:AbsListView.OnScrollListener{override fun onScrollStateChanged(v:AbsListView?,scrollState:Int){}
            override fun onScroll(v:AbsListView?,first:Int,count:Int,total:Int){if(count>0&&first+count>=total&&state!=null&&shown<state!!.snapshot.rows.count{it.parentId.isBlank()}){shown+=40;list.post{rebuild()}}}})
        tools.setOnClickListener{
            TokiDialogs.builder(context).setItems(arrayOf("缓存详情","联网补齐此视频（排队）",if(state?.paused==true)"继续此视频"else"暂停此视频","重试失败项","设置数量上限")){_,which->
                when(which){
                    0->{val s=state;val q=if(account.isBlank())null else OfflineCommentLimitOverrides.quotas(app,account,video)
                        TokiDialogs.builder(context).setTitle("缓存详情").setMessage("磁盘保存：${s?.storedRoots?:0} 条主评论、${s?.storedReplies?:0} 条回复\n当前范围：${q?.comments?:0} 主评论 / ${q?.replies?:0} 回复 / 每串 ${q?.perThread?:0}\n平台报告：${s?.snapshot?.total?:0}（不作为完成分母）\n结束原因：${s?.let{coverageExplanation(it.reason)}?:"读取中"}\n迁移异常页：${s?.migrationErrors?:0}\n长按评论可复制或提交翻译。\n此处的展开、滚动和模式切换均不联网。")
                            .setPositiveButton("知道了",null).show()}
                    1->explicit(CommentRepository.Command.Prepare)
                    2->if(state?.paused==true)explicit(CommentRepository.Command.Resume)else submit(CommentRepository.Command.Pause)
                    3->explicit(CommentRepository.Command.Retry)
                    4->if(account.isNotBlank())TokiDialogs.builder(context).setItems(arrayOf("主评论上限","回复总量和每串上限")){_,choice->
                        if(choice==0)OfflineCommentLimitDialog.show(context,OfflineCommentLimitOverrides.configured(app,account,video),true){value->
                            if(valid()){OfflineCommentLimitOverrides.set(app,account,video,value);source.register(model){refresh()}}
                        } else {
                            val q=OfflineCommentLimitOverrides.quotas(app,account,video)
                            OfflineCommentLimitDialog.replies(context,q.replies,q.perThread){total,thread->if(valid()){
                                ConfigClient.putValues(context,mapOf(OfflineCommentPreferences.REPLY_LIMIT to total.toString(),OfflineCommentPreferences.THREAD_LIMIT to thread.toString()))
                                source.register(model){refresh()}
                            }}
                        }
                    }.show()
                }
            }.show()
        }
        close.setOnClickListener{dialog.dismiss()}
        dialog.setOnShowListener{
            if(!readerWindowRegistered){readerWindowRegistered=true;openReaders.incrementAndGet()}
            val available=activity.findViewById<View>(android.R.id.content)?.height?.takeIf{it>0}?:activity.resources.displayMetrics.heightPixels
            dialog.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setGravity(Gravity.BOTTOM);setLayout(-1,(available*.72f).toInt());setDimAmount(.32f)}
            var startY=0f;var startHeight=0
            grip.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{startY=event.rawY;startHeight=dialog.window?.attributes?.height?:0;true};MotionEvent.ACTION_MOVE->{val h=(startHeight+startY-event.rawY).toInt().coerceIn((available*.45f).toInt(),(available*.90f).toInt());dialog.window?.setLayout(-1,h);true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->true;else->false}}
            HookRuntime.event("CommentReaderSheet","opened pinned video reader; waiting local initialization")
            source.register(model){result->if(valid())result.onSuccess{(a,v)->
                if(v!=video)return@onSuccess
                HookRuntime.event("CommentReaderSheet","local reader registered")
                account=a;subscription=source.observeVideo(a,video){main.removeCallbacks(refreshTick);main.postDelayed(refreshTick,120)};refresh()
            }.onFailure{notify("缓存入口不可用：${it.message}")}}
        }
        val accountGuard=object:Runnable { override fun run(){if(closed.get())return;if(!valid()){dialog.dismiss();return};main.postDelayed(this,1000)} }
        main.postDelayed(accountGuard,1000)
        dialog.setOnDismissListener{if(readerWindowRegistered){readerWindowRegistered=false;openReaders.decrementAndGet()};HookRuntime.event("CommentReaderSheet","dismissed activityEnded=${activity.isFinishing||activity.isDestroyed}");closed.set(true);main.removeCallbacks(accountGuard);subscription?.close();main.removeCallbacks(refreshTick);worker.shutdownNow();dismissed(dialog)}
        dialog.show();return dialog
    }
}
