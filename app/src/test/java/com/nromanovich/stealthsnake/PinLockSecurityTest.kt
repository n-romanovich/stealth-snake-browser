package com.nromanovich.stealthsnake

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.util.Base64
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinLockSecurityTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PinManager.resetHardwareKeystoreProviderForTests()
        SnakePreferences(ctx).resetToDefaults()
        PinLockCoordinator.resetForTests()
    }

    @After
    fun tearDown() {
        PinManager.resetHardwareKeystoreProviderForTests()
    }

    @Test
    fun `secondary activities enforce PIN overlay when locked and unlock cleanly`() {
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "2580")

        val settingsCtrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settingsAct = settingsCtrl.get()
        ShadowLooper.idleMainLooper()

        val contentRoot = settingsAct.findViewById<ViewGroup>(android.R.id.content)
        val settingsContent = contentRoot.getChildAt(0)
        assertEquals(View.INVISIBLE, settingsContent.visibility)
        assertNotNull(findTextViewWithText(contentRoot, ctx.getString(R.string.pin_title)))

        // Unlock with valid PIN restores Settings content
        "2580".forEach { ch -> findTextViewWithText(contentRoot, ch.toString())!!.performClick() }
        val unlockBtn = findButtonWithText(contentRoot, ctx.getString(R.string.pin_unlock))!!
        unlockBtn.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, settingsContent.visibility)
        assertNull(findTextViewWithText(contentRoot, ctx.getString(R.string.pin_title)))
        settingsCtrl.pause().stop().destroy()

        // CapturesActivity also locks on cold start while PIN enabled
        PinLockCoordinator.resetForTests()
        val capturesCtrl = Robolectric.buildActivity(CapturesActivity::class.java).setup()
        val capturesContent = capturesCtrl.get().findViewById<ViewGroup>(android.R.id.content)
        assertEquals(View.INVISIBLE, capturesContent.getChildAt(0).visibility)
        assertNotNull(findTextViewWithText(capturesContent, ctx.getString(R.string.pin_title)))
        capturesCtrl.pause().destroy()
    }

    @Test
    fun `intra-app navigation does not false-lock after 5s while true app background locks`() {
        var nowMs = 1_000L
        PinLockCoordinator.resetForTests { nowMs }
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        PinManager.setPin(prefs, "4321")

        // Launch and unlock MainActivity
        val mainCtrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val mainAct = mainCtrl.get()
        val rootFrame = mainAct.findViewById<ViewGroup>(R.id.rootFrame)
        val pinOverlay = rootFrame.getChildAt(rootFrame.childCount - 1)
        "4321".forEach { ch -> findTextViewWithText(pinOverlay, ch.toString())!!.performClick() }
        findButtonWithText(pinOverlay, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, mainAct.findViewById<View>(R.id.rootContainer).visibility)

        // Start SettingsActivity while MainActivity is still started -> transition pauses Main, starts Settings
        val settingsCtrl = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        mainCtrl.pause().stop() // MainActivity stops AFTER SettingsActivity started

        // Spend 60s inside Settings, then return to MainActivity -> MUST NOT lock
        nowMs += 60_000L
        mainCtrl.restart().start().resume()
        settingsCtrl.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
        assertFalse(PinLockCoordinator.isLocked(prefs))
        assertEquals(View.VISIBLE, mainAct.findViewById<View>(R.id.rootContainer).visibility)

        // Now truly background the whole app (all activities stopped) for 6 seconds -> MUST lock on return
        mainCtrl.pause().stop()
        nowMs += 6_000L
        mainCtrl.restart().start().resume()
        ShadowLooper.idleMainLooper()

        assertTrue(PinLockCoordinator.isLocked(prefs))
        assertEquals(View.INVISIBLE, mainAct.findViewById<View>(R.id.rootContainer).visibility)
        assertEquals(View.INVISIBLE, mainAct.findViewById<WebView>(R.id.webView).visibility)

        mainCtrl.pause().destroy()
    }

    @Test
    fun `MainActivity defers loading homeUrl until PIN is unlocked`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.homeUrl = "https://deferred.example.org/"
        PinManager.setPin(prefs, "9988")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val webView = activity.findViewById<WebView>(R.id.webView)
        assertNull("WebView must not load homeUrl before PIN unlock", Shadows.shadowOf(webView).lastLoadedUrl)

        val rootFrame = activity.findViewById<ViewGroup>(R.id.rootFrame)
        "9988".forEach { ch -> findTextViewWithText(rootFrame, ch.toString())!!.performClick() }
        findButtonWithText(rootFrame, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("https://deferred.example.org/", Shadows.shadowOf(webView).lastLoadedUrl)
        controller.pause().destroy()
    }

    @Test
    fun `PinManager verifyPinAsync executes on background executor and delivers result to main thread`() {
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "5678")
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<PinManager.VerifyResult?>()

        PinManager.verifyPinAsync(ctx, prefs, "5678", executor = executor) { res ->
            resultRef.set(res)
            latch.countDown()
        }

        // Wait for background executor to post onto main looper, then idle main looper
        val deadline = System.currentTimeMillis() + 5_000L
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        executor.shutdownNow()
        assertEquals(PinManager.VerifyResult.Success, resultRef.get())
    }

    @Test
    fun `settings showSetPinDialog validates confirmation PIN match`() {
        val prefs = SnakePreferences(ctx)
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        val root = (act.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        findButtonWithText(root, ctx.getString(R.string.pref_pin_set))!!.performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val edits = findAllEditTexts(dialog.window!!.decorView)
        assertEquals(2, edits.size)

        // Mismatch stays open and does not enable PIN
        edits[0].setText("1234")
        edits[1].setText("9999")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(prefs.pinLockEnabled)

        // Matching pair enables PIN cleanly
        edits[1].setText("1234")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(prefs.pinLockEnabled)
        assertTrue(PinManager.verifyPin(prefs, "1234") is PinManager.VerifyResult.Success)

        ctrl.pause().destroy()
    }

    private fun findAllEditTexts(view: View): List<EditText> = buildList {
        if (view is EditText) add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) addAll(findAllEditTexts(view.getChildAt(i)))
        }
    }

    private fun findButtonWithText(view: View, text: String): Button? {
        if (view is Button && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButtonWithText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun findTextViewWithText(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextViewWithText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun `wipeAllSensitiveDataFromSecondary erases encrypted session store, temp uploads, and clears sessionDirty`() {
        val prefs = SnakePreferences(ctx).apply {
            autoWipeOnExit = true
            sessionDirty = true
        }
        val sessionStore = EncryptedSessionStore(ctx)
        sessionStore.saveTabs(
            listOf(
                EncryptedSessionStore.SavedTab(
                    id = 7L,
                    url = "https://confidential.example/doc",
                    title = "Confidential",
                    sessionSeed = "seed-7",
                    presetOverride = null,
                    profileIdOverride = null
                )
            ),
            activeIndex = 0,
            autoWipeOnExit = true
        )
        sessionStore.addBookmark("https://bookmark.example/", "Private Bookmark")

        val uploadUri = Uri.parse("content://test.provider/secret_note.txt")
        Shadows.shadowOf(ctx.contentResolver).registerInputStream(
            uploadUri,
            ByteArrayInputStream("confidential-draft".toByteArray(Charsets.UTF_8))
        )
        val sanitized = UploadSanitizer.sanitize(ctx, uploadUri) as UploadSanitizer.SanitizeResult.Success
        val leakedUpload = File(sanitized.uri.path!!)
        assertTrue(leakedUpload.exists())

        PinLockCoordinator.wipeAllSensitiveDataFromSecondary(ctx)

        val reloadedStore = EncryptedSessionStore(ctx)
        assertNull(reloadedStore.loadTabs(autoWipeOnExit = true))
        assertNull(reloadedStore.loadTabs(autoWipeOnExit = false))
        assertTrue(reloadedStore.listBookmarks().isEmpty())
        assertFalse(leakedUpload.exists())
        assertFalse(prefs.sessionDirty)
    }

    @Test
    fun `showPinLockOverlay dismisses tracked dialogs, closes reader mode, and blocks late reader extraction`() {
        var nowMs = 1_000L
        PinLockCoordinator.resetForTests { nowMs }
        val prefs = SnakePreferences(ctx).apply {
            onboardingCompleted = true
            homeUrl = MainActivity.DEFAULT_START_URL
        }
        PinManager.setPin(prefs, "1234")

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val rootFrame = activity.findViewById<ViewGroup>(R.id.rootFrame)
        val pinOverlay = rootFrame.getChildAt(rootFrame.childCount - 1)
        "1234".forEach { ch -> findTextViewWithText(pinOverlay, ch.toString())!!.performClick() }
        findButtonWithText(pinOverlay, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()

        val activeTab = activity.createNewSession(loadHome = false, switchTo = true)
        val tabWebView = activity.findViewById<WebView>(R.id.webView)
        tabWebView.loadUrl("https://auth.example/panel")
        ShadowLooper.idleMainLooper()
        tabWebView.webViewClient.onReceivedHttpAuthRequest(tabWebView, null, "auth.example", "Admin")
        ShadowLooper.idleMainLooper()
        val authDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(authDialog.isShowing)

        activity.handleExtractedReaderArticle(
            activeTab,
            activeTab.navigationGeneration,
            ReaderModeHelper.Article("Article", "", listOf("Visible paragraph"))
        )
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isReaderOverlayOpenForTest())

        // Simulate app backgrounding past auto-lock grace period, then resume.
        ctrl.pause().stop()
        nowMs += 6_000L
        ctrl.restart().start().resume()
        ShadowLooper.idleMainLooper()

        assertTrue(PinLockCoordinator.isLocked(prefs))
        assertFalse("Tracked transient dialogs must be dismissed on PIN lock", authDialog.isShowing)
        assertFalse("Reader overlay must be closed on PIN lock", activity.isReaderOverlayOpenForTest())

        // Late async reader extraction callback arriving while locked must be dropped.
        activity.handleExtractedReaderArticle(
            activeTab,
            activeTab.navigationGeneration,
            ReaderModeHelper.Article("Late", "", listOf("Must stay hidden"))
        )
        ShadowLooper.idleMainLooper()
        assertFalse(activity.isReaderOverlayOpenForTest())

        ctrl.pause().destroy()
    }

    @Test
    fun `pbkdf2HmacSha256 matches JCA PBKDF2WithHmacSHA256 bit-for-bit across PINs, long keys, and salts`() {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val salts = listOf(
            ByteArray(1) { 0x42 },
            ByteArray(16) { (it * 13 + 7).toByte() },
            ByteArray(56) { (it * 17 + 1).toByte() },
            ByteArray(70) { (it * 29 + 3).toByte() }
        )
        val passwords = listOf(
            "1234",
            "000000",
            "99999999",
            "тест🔒123",
            "k".repeat(100)
        )
        val rounds = listOf(1, 2, 1000, 100_000)
        for (r in rounds) {
            for (salt in salts) {
                for (pw in passwords) {
                    if (r == 100_000 && salt.size != 16) continue
                    val spec = javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, r, 256)
                    val expected = try {
                        factory.generateSecret(spec).encoded
                    } finally {
                        spec.clearPassword()
                    }
                    val actual = PinManager.pbkdf2HmacSha256(pw.toCharArray(), salt, r, 256)
                    assertTrue(
                        "Mismatch for pwLen=${pw.length}, saltLen=${salt.size}, r=$r",
                        java.util.Arrays.equals(expected, actual)
                    )
                }
            }
        }
    }

    @Test
    fun `PinDialogHelper and PinLockCoordinator block background bypass and enforce atomic PIN state`() {
        var nowMs = 10_000L
        PinLockCoordinator.resetForTests { nowMs }
        PinLockCoordinator.ensureRegistered(ctx)
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "1234")
        PinLockCoordinator.markUnlocked()
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = ctrl.get()
        assertTrue(PinLockCoordinator.isActivityStarted(activity))

        var verified = false
        val dialog = PinDialogHelper.showVerifyCurrentPinDialog(activity, prefs, R.string.pin_verify_remove_title) {
            verified = true
        }
        ShadowLooper.idleMainLooper()
        assertFalse(PinLockCoordinator.isLocked(prefs))

        // Simulate activity stopping (backgrounded) before OK click completes verification.
        ctrl.pause().stop()
        assertFalse(PinLockCoordinator.isActivityStarted(activity))
        findAllEditTexts(dialog.window!!.decorView).single().setText("1234")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertFalse("Backgrounded verify dialog must not invoke onVerified", verified)
        assertFalse(dialog.isShowing)

        // Background timeout must still lock upon returning rather than having lastBackgroundAtMs wiped.
        nowMs += 6_000L
        ctrl.restart().start().resume()
        ShadowLooper.idleMainLooper()
        assertTrue("Returning after timeout must still require PIN unlock", PinLockCoordinator.isLocked(prefs))

        ctrl.pause().destroy()
    }

    private class FakeHardwareKeystoreProvider : PinManager.HardwareKeystoreProvider {
        val keys = linkedMapOf<String, ByteArray>()
        val deletedAliases = mutableListOf<String>()
        var failTransient = false
        var failCreate = false
        private var seq = 0

        override fun createHardwareHmacKey(): String? {
            if (failCreate) return null
            seq += 1
            val alias = "stealth_snake_pin_tee_v2_test_$seq"
            keys[alias] = ByteArray(32) { (seq * 31 + it).toByte() }
            return alias
        }

        override fun computeHmacTag(alias: String, salt: ByteArray, kdfOut: ByteArray): PinManager.HmacTagResult {
            if (failTransient) return PinManager.HmacTagResult.TransientError
            val secret = keys[alias] ?: return PinManager.HmacTagResult.PermanentKeyInvalidOrMissing
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
            mac.update("v2".toByteArray(Charsets.US_ASCII))
            mac.update(0.toByte())
            mac.update(byteArrayOf((salt.size ushr 24).toByte(), (salt.size ushr 16).toByte(), (salt.size ushr 8).toByte(), salt.size.toByte()))
            mac.update(salt)
            mac.update(0.toByte())
            mac.update(byteArrayOf((kdfOut.size ushr 24).toByte(), (kdfOut.size ushr 16).toByte(), (kdfOut.size ushr 8).toByte(), kdfOut.size.toByte()))
            mac.update(kdfOut)
            return PinManager.HmacTagResult.Success(mac.doFinal())
        }

        override fun deleteKey(alias: String) {
            if (keys.remove(alias) != null) {
                deletedAliases += alias
            }
        }
    }

    @Test
    fun `v2 hardware TEE Pepper-After-KDF protects credentials and fails closed on missing or transient key errors`() {
        val fakeTee = FakeHardwareKeystoreProvider()
        PinManager.hardwareKeystoreProvider = fakeTee
        val prefs = SnakePreferences(ctx)

        PinManager.setPin(prefs, "2580")
        assertEquals(SnakePreferences.PIN_VERSION_V2_TEE_PEPPER, prefs.pinVersion)
        val alias1 = prefs.pinKeyAlias
        assertTrue(alias1.isNotEmpty())
        assertTrue(fakeTee.keys.containsKey(alias1))
        // Stored digest must be HMAC(key, kdfOut), never raw v1 PBKDF2 output.
        assertNotEquals(PinManager.hashPin("2580", prefs.pinSalt), prefs.pinHash)
        assertTrue(PinManager.verifyPin(prefs, "2580") is PinManager.VerifyResult.Success)
        assertFalse(PinManager.verifyPin(prefs, "9999") is PinManager.VerifyResult.Success)
        assertEquals(1, prefs.pinFailedAttempts)

        // Transient Keystore outage fails closed AND does NOT burn failed attempts or trigger auto-wipe.
        fakeTee.failTransient = true
        val transientRes = PinManager.verifyPin(prefs, "2580") as PinManager.VerifyResult.Failed
        assertTrue(transientRes.keystoreError)
        assertEquals(1, prefs.pinFailedAttempts)
        fakeTee.failTransient = false

        // Even if prefs.pinHash is swapped back to raw v1 digest while pinVersion is 2, v2 MUST fail closed without fallback.
        val realV2Hash = prefs.pinHash
        prefs.pinHash = PinManager.hashPin("2580", prefs.pinSalt)
        assertFalse(PinManager.verifyPin(prefs, "2580") is PinManager.VerifyResult.Success)
        assertEquals(2, prefs.pinFailedAttempts)
        prefs.pinHash = realV2Hash

        // Deleting TEE key fails closed and never falls back to v1, without burning failed attempts.
        fakeTee.keys.clear()
        val missingKeyRes = PinManager.verifyPin(prefs, "2580") as PinManager.VerifyResult.Failed
        assertTrue(missingKeyRes.keystoreError)
        assertEquals(2, prefs.pinFailedAttempts)
    }

    @Test
    fun `v1 silently migrates to v2 on success while resetToDefaults and panic wipe honor key lifecycle`() {
        val fakeTee = FakeHardwareKeystoreProvider()
        // Start without TEE (legacy v1 user)
        fakeTee.failCreate = true
        PinManager.hardwareKeystoreProvider = fakeTee
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "1357")
        assertEquals(SnakePreferences.PIN_VERSION_V1_PBKDF2, prefs.pinVersion)
        assertEquals("", prefs.pinKeyAlias)

        // TEE becomes available: verifying correct PIN upgrades v1 -> v2 without locking session.
        fakeTee.failCreate = false
        PinLockCoordinator.markUnlocked()
        assertEquals(PinManager.VerifyResult.Success, PinManager.verifyPin(prefs, "1357"))
        assertFalse("Migration must not clear unlockedForSession", PinLockCoordinator.isLocked(prefs))
        assertEquals(SnakePreferences.PIN_VERSION_V2_TEE_PEPPER, prefs.pinVersion)
        val v2Alias = prefs.pinKeyAlias
        assertTrue(v2Alias.isNotEmpty())
        assertTrue(PinManager.verifyPin(prefs, "1357") is PinManager.VerifyResult.Success)

        // Panic wipe preserves PIN, version, alias, and hardware key.
        prefs.homeUrl = "https://sensitive.example/"
        prefs.scrubSensitiveDataForPanicWipe()
        assertEquals(SnakePreferences.PIN_VERSION_V2_TEE_PEPPER, prefs.pinVersion)
        assertEquals(v2Alias, prefs.pinKeyAlias)
        assertTrue(fakeTee.keys.containsKey(v2Alias))
        assertTrue(PinManager.verifyPin(prefs, "1357") is PinManager.VerifyResult.Success)

        // resetToDefaults(preservePin = true) preserves version, alias, and key.
        prefs.resetToDefaults(preservePin = true)
        assertEquals(SnakePreferences.PIN_VERSION_V2_TEE_PEPPER, prefs.pinVersion)
        assertEquals(v2Alias, prefs.pinKeyAlias)
        assertTrue(fakeTee.keys.containsKey(v2Alias))

        // Changing PIN generates a NEW unique alias and deletes previous alias AFTER commit.
        PinManager.setPin(prefs, "2468")
        val updatedAlias = prefs.pinKeyAlias
        assertNotEquals(v2Alias, updatedAlias)
        assertFalse(fakeTee.keys.containsKey(v2Alias))
        assertTrue(fakeTee.keys.containsKey(updatedAlias))

        // Cancelling setPin dialog mid-KDF (backgrounded) cleans up uncommitted alias & keeps active alias intact.
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        PinLockCoordinator.markUnlocked()
        val dlg = PinDialogHelper.showSetPinDialog(act, prefs, includeBody = true) {}
        ShadowLooper.idleMainLooper()
        val edits = findAllEditTexts(dlg.window!!.decorView)
        edits[0].setText("7777")
        edits[1].setText("7777")
        ctrl.pause().stop() // activity backgrounded before OK
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(updatedAlias, prefs.pinKeyAlias)
        assertEquals(setOf(updatedAlias), fakeTee.keys.keys.toSet())
        assertTrue(PinManager.verifyPin(prefs, "2468") is PinManager.VerifyResult.Success)
        ctrl.destroy()

        // resetToDefaults(preservePin = false) & clearPin delete Keystore alias completely.
        prefs.resetToDefaults(preservePin = false)
        assertEquals(SnakePreferences.PIN_VERSION_NONE, prefs.pinVersion)
        assertEquals("", prefs.pinKeyAlias)
        assertTrue(fakeTee.keys.isEmpty())
    }

    @Test
    fun `pbkdf2HmacSha256 matches official RFC 7914 and test vector`() {
        // RFC 6070 / OpenSSL PBKDF2-HMAC-SHA256 vector ("password", "salt", c=4096, dkLen=32)
        val derived = PinManager.pbkdf2HmacSha256(
            passwordChars = "password".toCharArray(),
            salt = "salt".toByteArray(Charsets.UTF_8),
            iterations = 4096,
            keyLengthBits = 256
        )
        val expectedHex = "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"
        val actualHex = derived.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, actualHex)
        assertTrue(Base64.encodeToString(derived, Base64.NO_WRAP).isNotEmpty())
        // Reject non-ASCII Unicode digits unreachable on PIN keypad
        assertFalse(PinManager.isValidPinFormat("\u0661\u0662\u0663\u0664"))
    }

    @Test
    fun `background lock dismisses floating secondary dialogs and keeps PIN unmodifiable`() {
        var nowMs = 1_000L
        PinLockCoordinator.resetForTests { nowMs }
        PinLockCoordinator.ensureRegistered(ctx)
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "2580")
        PinLockCoordinator.markUnlocked()

        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        ShadowLooper.idleMainLooper()

        val setDialog = PinDialogHelper.showSetPinDialog(act, prefs, includeBody = true) {}
        ShadowLooper.idleMainLooper()
        assertTrue(setDialog.isShowing)

        // Background past lock timeout, then resume -> overlay locks and dismisses floating dialog.
        ctrl.pause().stop()
        nowMs += 6_000L
        ctrl.restart().start().resume()
        ShadowLooper.idleMainLooper()

        assertTrue(PinLockCoordinator.isLocked(prefs))
        assertFalse("Secondary lock must dismiss floating AlertDialogs", setDialog.isShowing)
        assertTrue(PinManager.verifyPin(prefs, "2580") is PinManager.VerifyResult.Success)
        ctrl.pause().destroy()
    }

    @Test
    fun `destroyed or backgrounded activity mid-verify still executes TriggerAutoWipe without unlocking`() {
        val prefs = SnakePreferences(ctx).apply {
            pinAutoWipeOnFail = true
            homeUrl = "https://sensitive.example/"
            sessionDirty = true
        }
        PinManager.setPin(prefs, "1234")
        repeat(PinManager.MAX_FAILED_ATTEMPTS - 1) {
            PinManager.verifyPin(prefs, "0000")
        }

        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        ShadowLooper.idleMainLooper()
        val contentRoot = act.findViewById<ViewGroup>(android.R.id.content)
        "0000".forEach { ch -> findTextViewWithText(contentRoot, ch.toString())!!.performClick() }

        // Destroy activity right as 5th wrong PIN runs (simulating rotation mid-KDF).
        val bgExecutor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        PinManager.verifyPinAsync(act, prefs, "0000", executor = bgExecutor) { res ->
            if (res is PinManager.VerifyResult.TriggerAutoWipe) {
                PinLockCoordinator.executeDetachedAutoWipe(act.applicationContext)
            }
            latch.countDown()
        }
        ctrl.pause().stop().destroy()
        assertTrue(act.isDestroyed)

        val deadline = System.currentTimeMillis() + 5_000L
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        bgExecutor.shutdownNow()

        assertEquals(MainActivity.DEFAULT_START_URL, prefs.homeUrl)
        assertFalse(prefs.sessionDirty)
        assertFalse(prefs.pinAutoWipePending)
        assertTrue("Detached auto-wipe must leave PIN lock armed", PinLockCoordinator.isLocked(prefs))
    }

    @Test
    fun `permanent key loss sets keyPermanentlyLost and wipe-reset clears data before PIN`() {
        val fakeTee = FakeHardwareKeystoreProvider()
        PinManager.hardwareKeystoreProvider = fakeTee
        val prefs = SnakePreferences(ctx).apply {
            homeUrl = "https://secret.example/"
            pinAutoWipeOnFail = true
        }
        PinManager.setPin(prefs, "4321")
        fakeTee.keys.clear()

        // Transient error keeps attempts intact and does NOT mark key permanently lost.
        fakeTee.failTransient = true
        val trans = PinManager.verifyPin(prefs, "4321") as PinManager.VerifyResult.Failed
        assertTrue(trans.keystoreError)
        assertFalse(trans.keyPermanentlyLost)
        fakeTee.failTransient = false

        // Permanent loss surfaces Wipe & Reset button on lock overlay.
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        val root = act.findViewById<ViewGroup>(android.R.id.content)
        "4321".forEach { ch -> findTextViewWithText(root, ch.toString())!!.performClick() }
        findButtonWithText(root, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()

        val wipeBtn = findButtonWithText(root, ctx.getString(R.string.pin_wipe_reset_btn))
        assertNotNull(wipeBtn)
        assertEquals(View.VISIBLE, wipeBtn!!.visibility)

        wipeBtn.performClick()
        ShadowLooper.idleMainLooper()
        val confirmDlg = ShadowDialog.getLatestDialog() as AlertDialog
        confirmDlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(MainActivity.DEFAULT_START_URL, prefs.homeUrl)
        assertFalse(prefs.pinLockEnabled)
        ctrl.pause().destroy()
    }

    @Test
    fun `MainActivity stopped mid-TriggerAutoWipe stays locked, wipes data, and leaves keypad responsive on return`() {
        val prefs = SnakePreferences(ctx).apply {
            onboardingCompleted = true
            pinAutoWipeOnFail = true
            homeUrl = "https://sensitive.example.org/"
        }
        PinManager.setPin(prefs, "4321")
        repeat(PinManager.MAX_FAILED_ATTEMPTS - 1) {
            PinManager.verifyPin(prefs, "0000")
        }

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        ShadowLooper.idleMainLooper()
        val rootFrame = act.findViewById<ViewGroup>(R.id.rootFrame)
        assertTrue(PinLockCoordinator.isLocked(prefs))

        // Arm pending auto-wipe & pause/stop MainActivity before returning (simulates backgrounding mid-5th verify)
        assertEquals(PinManager.VerifyResult.TriggerAutoWipe, PinManager.verifyPin(prefs, "0000"))
        assertTrue(prefs.pinAutoWipePending)
        ctrl.pause().stop()
        assertFalse(PinLockCoordinator.isActivityStarted(act))

        // Returning to MainActivity triggers enforceLockIfNeeded -> wipes sensitive prefs, stays locked, keeps keypad responsive
        ctrl.restart().start().resume()
        ShadowLooper.idleMainLooper()

        assertEquals(MainActivity.DEFAULT_START_URL, prefs.homeUrl)
        assertFalse(prefs.pinAutoWipePending)
        assertTrue("Resuming after detached auto-wipe must stay locked", PinLockCoordinator.isLocked(prefs))

        // Overlay keypad is NOT wedged and unlocks with valid PIN (search pinOverlay directly so "1" doesn't hit tabCountBadge)
        val pinOverlay = rootFrame.getChildAt(rootFrame.childCount - 1)
        "4321".forEach { ch -> findTextViewWithText(pinOverlay, ch.toString())!!.performClick() }
        findButtonWithText(pinOverlay, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()

        assertFalse(PinLockCoordinator.isLocked(prefs))
        assertEquals(View.VISIBLE, act.findViewById<View>(R.id.rootContainer).visibility)
        ctrl.pause().destroy()
    }

    @Test
    fun `5 failed attempts with LOCK_FOREVER triggers irreversible hard lock and shows hard-lock UI`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "4321")
        prefs.commitPinFailurePolicy(PinFailureAction.LOCK_FOREVER, autoWipeOnFail = true)

        repeat(4) {
            val res = PinManager.verifyPin(prefs, "0000")
            assertTrue(res is PinManager.VerifyResult.Failed)
            assertFalse(prefs.pinHardLocked)
        }

        val fifth = PinManager.verifyPin(prefs, "0000")
        assertEquals(PinManager.VerifyResult.TriggerHardLock, fifth)
        assertTrue(prefs.pinHardLocked)

        // Even the correct PIN cannot unlock once hard-locked
        assertEquals(PinManager.VerifyResult.TriggerHardLock, PinManager.verifyPin(prefs, "4321"))

        // Resetting defaults with preservePin=true and markUnlocked() cannot bypass hard lock
        prefs.resetToDefaults(preservePin = true)
        assertTrue(prefs.pinHardLocked)
        assertEquals(PinFailureAction.LOCK_FOREVER, prefs.pinFailureAction)
        PinLockCoordinator.markUnlocked()
        assertTrue(PinLockCoordinator.isLocked(prefs))

        // Overlay renders Hard-Lock UI without keypad/Unlock button
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        ShadowLooper.idleMainLooper()
        val rootFrame = act.findViewById<android.widget.FrameLayout>(R.id.rootFrame)
        val pinOverlay = rootFrame.getChildAt(rootFrame.childCount - 1)
        assertNotNull(findTextViewWithText(pinOverlay, ctx.getString(R.string.pin_hard_lock_title)))
        assertNotNull(findButtonWithText(pinOverlay, ctx.getString(R.string.pin_hard_lock_open_settings)))
        assertNull(findButtonWithText(pinOverlay, ctx.getString(R.string.pin_unlock)))
        assertEquals(View.INVISIBLE, act.findViewById<View>(R.id.rootContainer).visibility)
        ctrl.pause().destroy()
    }

    @Test
    fun `setting PIN opens failure action policy dialog and commits selected action`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        PinLockCoordinator.markUnlocked()

        var savedCalled = false
        val setupDlg = PinDialogHelper.showSetPinDialog(act, prefs) {
            savedCalled = true
        }
        ShadowLooper.idleMainLooper()

        val edits = mutableListOf<android.widget.EditText>()
        fun collectEdits(v: View) {
            if (v is android.widget.EditText) edits.add(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) collectEdits(v.getChildAt(i))
            }
        }
        collectEdits(setupDlg.window!!.decorView)
        edits[0].setText("1357")
        edits[1].setText("1357")
        setupDlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertTrue("onPinSaved should update caller UI immediately upon PIN commit", savedCalled)
        assertTrue(prefs.pinPolicySelectionPending)

        // Second dialog (policy picker) is now showing on top
        val policyDlg = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(policyDlg.isShowing)
        val lockOption = findTextViewWithText(policyDlg.window!!.decorView, ctx.getString(R.string.pin_failure_lock_title))!!
        (lockOption.parent.parent as View).performClick()
        policyDlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertFalse(prefs.pinPolicySelectionPending)
        assertEquals(PinFailureAction.LOCK_FOREVER, prefs.pinFailureAction)
        assertTrue(prefs.pinAutoWipeOnFail)
        ctrl.pause().destroy()
    }
}
