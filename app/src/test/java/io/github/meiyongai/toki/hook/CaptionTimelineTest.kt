package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionTimelineTest {
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
}
