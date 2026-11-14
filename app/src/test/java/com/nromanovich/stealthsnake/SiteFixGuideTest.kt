package com.nromanovich.stealthsnake

import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SiteFixGuideTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every guide entry has localized strings and bilingual keywords`() {
        assertEquals(16, SiteFixGuide.entries.size)
        for (entry in SiteFixGuide.entries) {
            assertTrue(ctx.getString(entry.titleRes).isNotBlank())
            assertTrue(ctx.getString(entry.symptomsRes).isNotBlank())
            assertTrue(ctx.getString(entry.causeRes).isNotBlank())
            assertTrue(ctx.getString(entry.fixPathRes).isNotBlank())
            assertTrue("${entry.id} missing EN keywords", entry.keywordsEn.isNotEmpty())
            assertTrue("${entry.id} missing RU keywords", entry.keywordsRu.isNotEmpty())
        }
    }

    @Test
    fun `bilingual keyword search finds topics even when keywords are not in title`() {
        val turnstile = SiteFixGuide.search(ctx, "turnstile")
        assertTrue(turnstile.isNotEmpty())
        assertEquals("canvas_webgl_captcha", turnstile.first().entry.id)

        val rusVoice = SiteFixGuide.search(ctx, "дискорд")
        assertTrue(rusVoice.isNotEmpty())
        assertEquals("webrtc_calls", rusVoice.first().entry.id)

        val sbp = SiteFixGuide.search(ctx, "сбп")
        assertTrue(sbp.isNotEmpty())
        assertEquals("external_schemes", sbp.first().entry.id)

        val router = SiteFixGuide.search(ctx, "phpmyadmin")
        assertTrue(router.isNotEmpty())
        assertEquals("js_dialogs", router.first().entry.id)
    }

    @Test
    fun `multi-token AND query narrows results accurately`() {
        val broad = SiteFixGuide.search(ctx, "captcha")
        assertTrue(broad.size >= 2)

        val narrow = SiteFixGuide.search(ctx, "captcha figma")
        assertEquals(1, narrow.size)
        assertEquals("canvas_webgl_captcha", narrow.first().entry.id)
    }

    @Test
    fun `fuzzy search tolerates typos and normalizes yo to e`() {
        val typoCanvas = SiteFixGuide.search(ctx, "канваз")
        assertTrue(typoCanvas.any { it.entry.id == "canvas_webgl_captcha" })

        val typoCloudflare = SiteFixGuide.search(ctx, "clodflare")
        assertTrue(typoCloudflare.any { it.entry.id == "canvas_webgl_captcha" })

        val yoNorm = SiteFixGuide.search(ctx, "дергается")
        assertTrue(yoNorm.any { it.entry.id == "perf_timing" })
    }

    @Test
    fun `category filter restricts results to selected category`() {
        val vpnOnly = SiteFixGuide.search(ctx, "", FixCategory.VPN)
        assertEquals(1, vpnOnly.size)
        assertEquals("vpn_kill_switch_blocked", vpnOnly.first().entry.id)

        val wrongCategory = SiteFixGuide.search(ctx, "webrtc", FixCategory.SESSION)
        assertTrue(wrongCategory.isEmpty())
    }

    @Test
    fun `highlight attaches BackgroundColorSpan for matching query tokens`() {
        val highlighted = SiteFixGuide.highlight("Cloudflare Turnstile captcha", "turnstile", Color.GREEN)
        assertTrue(highlighted is Spanned)
        val spans = (highlighted as Spanned).getSpans(0, highlighted.length, BackgroundColorSpan::class.java)
        assertFalse(spans.isEmpty())
    }
}
