package io.github.meiyongai.toki.subtitle.hook

/** Pure playback state. No wall-clock expiration, views, transport or competing caption owners. */
internal class SubtitleDisplayController {
    data class Frame(val video:String,val pass:Long,val track:String,val text:String,val source:String,
        val startMs:Long?=null,val endMs:Long?=null)
    private var video="";private var pass=-1L;private var track=""
    private var native="";private var timelineReady=false;private var timeline:CaptionTimeline.Cue?=null
    fun select(video:String,pass:Long,track:String):Boolean {
        if(this.video==video&&this.pass==pass&&this.track==track)return false
        this.video=video;this.pass=pass;this.track=track;native="";timeline=null;timelineReady=false;return true
    }
    fun native(video:String,pass:Long,text:String,visible:Boolean):Frame {
        if(video==this.video&&pass==this.pass)native=if(visible)text else ""
        return frame()
    }
    fun progress(video:String,pass:Long,ready:Boolean,cue:CaptionTimeline.Cue?):Frame {
        if(video==this.video&&pass==this.pass) {
            timelineReady=ready;timeline=if(ready)cue else null
            // A verified gap owns an empty frame; an unavailable/mismatched timeline falls back to current native input.
        }
        return frame()
    }
    fun frame():Frame=if(timelineReady)Frame(video,pass,track,timeline?.text.orEmpty(),"timeline",timeline?.startMs,timeline?.endMs)
        else Frame(video,pass,track,native,"native")
}
