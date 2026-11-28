package com.alraad.feature.player.impl

import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Evidence for #83's emulator-lane failures: do the exact fixture bytes served to the
 * player decode to cues covering the 0-4s window under ExoPlayer's own parsers?
 * Pure JVM: distinguishes fixture faults from pipeline defects without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleFixtureDecodeEvidenceTest {
    private fun parseAll(
        parser: SubtitleParser,
        bytes: ByteArray,
    ): List<CuesWithTiming> {
        val out = mutableListOf<CuesWithTiming>()
        parser.parse(
            bytes,
            0,
            bytes.size,
            SubtitleParser.OutputOptions.allCues(),
        ) { out.add(it) }
        return out
    }

    private fun assertCueCovers(
        name: String,
        parser: SubtitleParser,
        body: String,
        positionUs: Long,
    ) {
        val all = parseAll(parser, body.toByteArray(Charsets.UTF_8))
        val cueTexts = all.flatMap { it.cues }.mapNotNull { it.text?.toString() }
        val covers =
            all.any { cwt ->
                positionUs >= cwt.startTimeUs && positionUs <= cwt.endTimeUs
            }
        println("EVIDENCE $name: cues=${cueTexts.size} texts=$cueTexts covers${positionUs}us=$covers")
        assertTrue("$name produced no cues", cueTexts.isNotEmpty())
        assertTrue("$name has no cue covering ${positionUs}us", covers)
    }

    @Test
    fun vttFixtureCoversTestPosition() {
        assertCueCovers(
            "vtt",
            WebvttParser(),
            "WEBVTT\n\n00:00.000 --> 00:04.000\nHello from VTT\n",
            2_000_000L,
        )
    }

    @Test
    fun srtFixtureCoversTestPosition() {
        assertCueCovers(
            "srt",
            SubripParser(),
            "1\n00:00:00,000 --> 00:00:04,000\nHello from SRT\n",
            2_000_000L,
        )
    }

    @Test
    fun assFixtureCoversTestPosition() {
        val ass =
            "[Script Info]\nScriptType: v4.00+\n\n" +
                "[V4+ Styles]\n" +
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour," +
                " Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow," +
                " Alignment, MarginL, MarginR, MarginV, Encoding\n" +
                "Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2," +
                "10,10,10,1\n\n" +
                "[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,Hello from ASS\n"
        assertCueCovers("ass", SsaParser(), ass, 2_000_000L)
    }
}
