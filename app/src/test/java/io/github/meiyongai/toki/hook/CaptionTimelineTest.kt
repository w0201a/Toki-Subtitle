package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionTimelineTest {
    @Test fun playbackPositionChoosesOnlyTheActiveCueIncludingSeekAndLoop() {
        val cues = listOf(CaptionTimeline.Cue(100, 500, "first"), CaptionTimeline.Cue(800, 1200, "second"))
        assertNull(CaptionTimeline.at(cues, -1))
        assertNull(CaptionTimeline.at(cues, 99))
        assertEquals("first", CaptionTimeline.at(cues, 100))
        assertNull(CaptionTimeline.at(cues, 500))
        assertEquals("second", CaptionTimeline.at(cues, 900))
        assertNull(CaptionTimeline.at(cues, 1500))
        assertEquals("first", CaptionTimeline.at(cues, 150))
    }
    @Test fun webVttAndSrtKeepTimingAndMultilineText() {
        val source = "WEBVTT\n\n00:01.000 --> 00:03.250 align:start\nHello\nworld\n\n00:00:04,000 --> 00:00:05,500\n<b>Next</b> &amp; last\n"
        assertEquals(listOf(CaptionTimeline.Cue(1000, 3250, "Hello\nworld"),
            CaptionTimeline.Cue(4000, 5500, "Next & last")), CaptionTimeline.parse(source))
    }
    @Test fun nativeJsonRequiresRealTimesAndBoundsInput() {
        assertEquals(listOf(CaptionTimeline.Cue(100, 900, "hello")), CaptionTimeline.parse(
            "{\"utterances\":[{\"start_time\":100,\"end_time\":900,\"text\":\"hello\"},{\"text\":\"invalid\"}]}"))
        assertTrue(CaptionTimeline.parse("unrecognized").isEmpty())
        assertTrue(CaptionTimeline.parse("x".repeat(1_048_577)).isEmpty())
    }
    @Test fun shortOverlappingMovieCueDoesNotHideTheStillActiveLongCue() {
        val index = CaptionTimeline.Index(listOf(CaptionTimeline.Cue(1000, 10000, "long dialogue"),
            CaptionTimeline.Cue(2000, 2500, "short overlay"), CaptionTimeline.Cue(12000, 13000, "next")))
        assertEquals("short overlay", index.at(2400))
        assertEquals("long dialogue", index.at(2500))
        assertEquals("long dialogue", index.at(9999))
        assertNull(index.at(10000))
        assertNull(index.at(11999))
        assertEquals("next", index.at(12000))
        assertEquals("long dialogue", index.at(1500))
    }
}
