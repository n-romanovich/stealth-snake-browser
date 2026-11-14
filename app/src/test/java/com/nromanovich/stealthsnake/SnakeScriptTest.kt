package com.nromanovich.stealthsnake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnakeScriptTest {

    private fun resolveAsset(name: String): File {
        var dir = File(".").canonicalFile
        repeat(5) {
            val candidate = File(dir, "app/src/main/assets/$name")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return candidate
        }
        error("Could not locate asset $name")
    }

    @Test
    fun `generateSessionSeed returns 8 lowercase hex characters and varies`() {
        val seeds = (1..20).map { SnakeScript.generateSessionSeed() }.toSet()
        for (seed in seeds) {
            assertTrue("Invalid seed format: $seed", seed.matches(Regex("^[0-9a-f]{8}$")))
        }
        assertTrue("Expected distinct random seeds", seeds.size > 1)
    }

    @Test
    fun `build injects config into snake_core js cleanly`() {
        val template = resolveAsset("snake_core.js").readText(Charsets.UTF_8)
        val rules = resolveAsset("blocklist.txt").inputStream().use { BlocklistEngine.fromStream(it).rules }
        val built = SnakeScript.build(template, "deadbeef", rules)

        assertFalse(built.contains("__SS_CONFIG_JSON__"))
        assertTrue(built.contains("\"seed\":\"deadbeef\""))
        assertTrue(built.contains("||google-analytics.com"))
        assertNotEquals(template, built)
    }

    @Test(expected = IllegalStateException::class)
    fun `build fails when placeholder token appears more than once`() {
        SnakeScript.build("__SS_CONFIG_JSON__ __SS_CONFIG_JSON__", "0123abcd", emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build validates session seed format`() {
        SnakeScript.build("const C = __SS_CONFIG_JSON__;", "not-hex!", emptyList())
    }

    @Test
    fun `build supports blockWebSockets and snake_core avoids eval`() {
        val template = resolveAsset("snake_core.js").readText(Charsets.UTF_8)
        val built = SnakeScript.build(
            template,
            "0123abcd",
            emptyList(),
            SnakeScript.FeatureFlags(
                spoofTimezone = false,
                noiseDomRect = false,
                blockWebSockets = true
            )
        )
        assertTrue(built.contains("\"blockWebSockets\":true"))
        assertTrue(built.contains("\"spoofTimezone\":false"))
        assertTrue(built.contains("\"noiseDomRect\":false"))
        assertFalse("snake_core.js must not call win.eval() for CSP compatibility", template.contains("win.eval('('"))
        assertTrue(template.contains("WEBGL_debug_renderer_info") || template.contains("0x9245"))
        assertTrue(template.contains("getClientRects"))
        assertTrue(template.contains("measureText"))
        assertTrue(template.contains("getByteFrequencyData"))
        assertTrue(template.contains("SharedWorker"))
        assertTrue(template.contains("isSolidImage"))
    }
}
