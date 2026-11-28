package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.AdblockPlusDomainParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockPlusDomainParserTest {
    @Test fun `extracts literal domain rules and skips everything else`() {
        val text =
            """
            ! Title: EasyList
            [Adblock Plus 2.0]
            ||ads.example.com^
            ||track.example.net^${'$'}third-party
            @@||allowed.example.com^
            example.com##.ad-banner
            example.com#@#.ad-banner
            /banner/*/ad.js
            |http://prefix-only.example.org/
            """.trimIndent()
        val domains = AdblockPlusDomainParser.parse(text)
        assertTrue("ads.example.com" in domains)
        // Deliberate change from the earlier subset: a `${'$'}third-party` rule is no longer widened to a
        // whole-domain block, see the dedicated third-party test below.
        assertFalse("track.example.net" in domains)
        assertFalse("allowed.example.com" in domains)
        assertFalse(domains.any { it.contains('/') || it.contains('*') })
        assertEquals(setOf("ads.example.com"), domains)
    }

    @Test fun `accepts hosts-format lines through the same parser`() {
        val domains = AdblockPlusDomainParser.parse("0.0.0.0 ads.example.com\n127.0.0.1 track.example.net")
        assertEquals(setOf("ads.example.com", "track.example.net"), domains)
    }

    @Test fun `rules narrowed by path or options are unsupported, not widened to the whole domain`() {
        val text =
            """
            ||youtube.com/pagead/
            ||google.com/adsense/search/ads.js
            ||linkedin.com^${'$'}third-party
            ||example.org^${'$'}script,domain=~example.net
            ||cnstrc.com^${'$'}badfilter
            ||googleapis.com^*/gen_204?
            ||ads.example.com^
            """.trimIndent()
        val parsed = AdblockPlusDomainParser.parseDetailed(text)
        assertEquals(setOf("ads.example.com"), parsed.domains)
        assertEquals(6, parsed.unsupportedNetwork)
    }

    @Test fun `counts cosmetic scriptlet and exception rules separately`() {
        val parsed =
            AdblockPlusDomainParser.parseDetailed(
                "a.example.com##.ad\nb.example.com##+js(trusted-click-element, #x)\n@@||c.example.com^\n||d.example.com^",
            )
        assertEquals(setOf("d.example.com"), parsed.domains)
        assertEquals(2, parsed.cosmeticOrScriptlet)
        assertEquals(1, parsed.exceptions)
    }

    @Test fun `a block that a same-list exception names is dropped instead of left broader than intended`() {
        val text =
            """
            ||example.com^
            @@||cdn.example.com^${'$'}script
            ||ads.other.net^
            @@||other.net/allowed/
            ||keep.example.org^
            """.trimIndent()
        val parsed = AdblockPlusDomainParser.parseDetailed(text)
        assertEquals(setOf("keep.example.org"), parsed.domains)
        assertEquals(2, parsed.droppedByException)
    }

    @Test fun `third-party-only rules are rejected because a domain block cannot honour the third-party condition`() {
        // `||linkedin.com^${'$'}third-party` means "block linkedin.com only when it is loaded from another
        // site". The engine decides per URL with no page context, so admitting it would block
        // linkedin.com itself, including when the user opens it. Rejecting trades some coverage
        // for never breaking a first-party site.
        val parsed = AdblockPlusDomainParser.parseDetailed("||linkedin.com^${'$'}third-party\n||ads.example.com^")
        assertEquals(setOf("ads.example.com"), parsed.domains)
        assertEquals(1, parsed.unsupportedNetwork)
    }

    @Test fun `badfilter drops an otherwise admitted pure domain rule`() {
        val parsed = AdblockPlusDomainParser.parseDetailed("||cnstrc.com^\n||cnstrc.com^${'$'}badfilter\n||keep.example.org^")
        assertEquals(setOf("keep.example.org"), parsed.domains)
        assertEquals(1, parsed.droppedByException)
    }

    @Test fun `withoutGuarded removes same host, subdomain and parent overlaps only`() {
        val blocked = setOf("example.com", "ads.deep.other.net", "keep.example.org")
        val guards = setOf("cdn.example.com", "other.net")
        assertEquals(setOf("keep.example.org"), AdblockPlusDomainParser.withoutGuarded(blocked, guards))
    }
}
