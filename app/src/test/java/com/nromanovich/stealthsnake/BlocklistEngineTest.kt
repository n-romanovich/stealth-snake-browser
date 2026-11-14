package com.nromanovich.stealthsnake

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlocklistEngineTest {

    private fun resolveProjectFile(relative: String): File {
        var dir = File(".").canonicalFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return candidate
        }
        error("Could not locate $relative from ${File(".").canonicalPath}")
    }

    @Test
    fun `matches shared blocklist_cases json identically to snake_core js`() {
        val blocklistFile = resolveProjectFile("app/src/main/assets/blocklist.txt")
        val casesFile = resolveProjectFile("testdata/blocklist_cases.json")

        val engine = blocklistFile.inputStream().use { BlocklistEngine.fromStream(it) }
        val cases = JSONArray(casesFile.readText(Charsets.UTF_8))
        assertTrue(cases.length() >= 15)

        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val url = item.getString("url")
            val subresource = item.getBoolean("subresource")
            val expectedBlocked = item.getBoolean("blocked")

            assertEquals(
                "Mismatch for url=$url subresource=$subresource",
                expectedBlocked,
                engine.isBlocked(url, isForMainFrame = !subresource)
            )
        }
    }

    @Test
    fun `ignores comments blanks and invalid inputs`() {
        val engine = BlocklistEngine.fromRules(
            listOf(
                "# comment",
                "   ",
                "||Tracker.COM.",
                "path:/collect"
            )
        )
        assertEquals(listOf("||tracker.com", "path:/collect"), engine.rules)
        assertFalse(engine.isBlocked(null as String?, isForMainFrame = false))
        assertFalse(engine.isBlocked("", isForMainFrame = false))
        assertTrue(engine.isBlocked("https://sub.tracker.com/pixel", isForMainFrame = false))
        assertFalse(engine.isBlocked("https://sub.tracker.com/pixel", isForMainFrame = true))
    }

    @Test
    fun `empty blocked response has 200 OK text plain and empty stream`() {
        val engine = BlocklistEngine.fromRules(emptyList())
        val resp = engine.createEmptyBlockedResponse()
        assertEquals("text/plain", resp.mimeType)
        assertEquals("utf-8", resp.encoding)
        assertEquals(200, resp.statusCode)
        assertEquals("no-store", resp.responseHeaders["Cache-Control"])
        assertEquals(0, resp.data.readBytes().size)
    }

    @Test
    fun `deduplicates rules handles backslash authority smuggling and scales to large rulesets`() {
        val rules = (1..250).map { "||tracker$it.example" } + listOf(
            "||Tracker1.example",
            "path:/collect",
            "path:/collect"
        )
        val engine = BlocklistEngine.fromRules(rules)
        assertEquals(251, engine.rules.size)
        assertTrue(engine.isBlocked("https://sub.tracker250.example/px.png", isForMainFrame = false))
        assertTrue(engine.isBlocked("https://tracker1.example\\@benign.org/pixel.js", isForMainFrame = false))
        assertFalse(engine.isBlocked("https://benign.org\\@tracker1.example/ok.js", isForMainFrame = false))
    }
}
