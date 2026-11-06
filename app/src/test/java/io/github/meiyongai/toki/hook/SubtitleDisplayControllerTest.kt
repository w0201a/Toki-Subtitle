package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class SubtitleDisplayControllerTest {
    @Test fun aLongNativeLineAndPauseHaveNoWallClockExpiration() {
        val c=SubtitleDisplayController();c.select("v",1,"track")
        repeat(100){assertEquals("long sentence",c.native("v",1,"long sentence",true).text)}
        repeat(100){assertEquals("long sentence",c.frame().text)}
    }
    @Test fun timelineGapIsAuthoritativeAndNativeCannotTakeOver() {
        val c=SubtitleDisplayController();c.select("v",1,"t");c.native("v",1,"old",true)
        assertEquals("new",c.progress("v",1,true,CaptionTimeline.Cue(1000,3000,"new")).text)
        assertEquals("new",c.native("v",1,"old",true).text)
        assertEquals("",c.progress("v",1,true,null).text)
        assertEquals("",c.native("v",1,"old",true).text)
        assertEquals("timeline",c.frame().source)
    }
    @Test fun forwardSeekRewindLoopAndNeighbourFramesCannotRetainPreviousCue() {
        val c=SubtitleDisplayController();c.select("v",1,"t")
        c.progress("v",1,true,CaptionTimeline.Cue(0,1000,"first"))
        assertEquals("last",c.progress("v",1,true,CaptionTimeline.Cue(9000,10000,"last")).text)
        c.select("v",2,"t");assertEquals("",c.frame().text)
        c.native("v",1,"late previous pass",true);assertEquals("",c.frame().text)
        assertEquals("first",c.progress("v",2,true,CaptionTimeline.Cue(0,1000,"first")).text)
        assertEquals("first",c.native("neighbour",2,"wrong",true).text)
    }
    @Test fun trackAndVideoSwitchClearTimelineAndAcceptOnlyNewInput() {
        val c=SubtitleDisplayController();c.select("v",1,"a");c.progress("v",1,true,CaptionTimeline.Cue(0,1000,"old"))
        c.select("v",1,"b");assertEquals("",c.frame().text)
        assertEquals("fresh",c.native("v",1,"fresh",true).text)
        c.select("other",2,"c");assertEquals("",c.frame().text)
        assertEquals("",c.progress("v",1,true,CaptionTimeline.Cue(0,1000,"late")).text)
    }
    @Test fun emptyNativeFrameHidesAndUnavailableTimelineFallsBackToTheLatestNativeInput() {
        val c=SubtitleDisplayController();c.select("v",1,"t")
        c.native("v",1,"native",true);assertEquals("",c.native("v",1,"native",false).text)
        c.progress("v",1,true,CaptionTimeline.Cue(0,1000,"current"))
        c.native("v",1,"fresh native",true)
        assertEquals("fresh native",c.progress("v",1,false,null).text)
        assertEquals("native",c.frame().source)
    }
}
