package com.nromanovich.stealthsnake

import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnakeClientAndChromeTest {

    private fun fakeRequest(url: String, isMainFrame: Boolean): WebResourceRequest {
        val uri = Uri.parse(url)
        return object : WebResourceRequest {
            override fun getUrl(): Uri = uri
            override fun isForMainFrame(): Boolean = isMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
    }

    @Test
    fun `SnakeClient intercepts blocked subresources and passes clean requests`() {
        val blocklist = BlocklistEngine.fromRules(listOf("||fingerprint.com", "path:/collect"))
        val blockedUrls = mutableListOf<String>()
        val client = SnakeClient(blocklist, onBlockedCallback = { blockedUrls.add(it) })

        val blockedResp = client.shouldInterceptRequest(null, fakeRequest("https://cdn.fingerprint.com/v3.js", false))
        assertNotNull(blockedResp)
        assertEquals(200, blockedResp!!.statusCode)
        assertEquals(1, blockedUrls.size)

        val pathBlocked = client.shouldInterceptRequest(null, fakeRequest("https://example.org/collect/v1", false))
        assertNotNull(pathBlocked)
        assertEquals(2, blockedUrls.size)

        val allowedMain = client.shouldInterceptRequest(null, fakeRequest("https://fingerprint.com/", true))
        assertNull(allowedMain)

        val allowedSub = client.shouldInterceptRequest(null, fakeRequest("https://example.org/app.js", false))
        assertNull(allowedSub)
    }

    @Test
    fun `SnakeClient blocks external custom URI schemes and allows http https about`() {
        val client = SnakeClient(BlocklistEngine.fromRules(emptyList()))

        assertFalse(client.shouldOverrideUrlLoading(null, fakeRequest("https://example.org/", true)))
        assertFalse(client.shouldOverrideUrlLoading(null, fakeRequest("http://example.org/", true)))
        assertFalse(client.shouldOverrideUrlLoading(null, fakeRequest("about:blank", true)))

        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("intent://scan/#Intent;scheme=zxing;end", true)))
        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("tel:+123456789", true)))
        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("mailto:user@example.org", true)))
        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("file:///etc/hosts", true)))
        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("content://media/external/images/media/1", true)))
        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("javascript:alert(1)", true)))
    }

    @Test
    fun `SnakeChrome suppresses dialogs denies permissions and closes file chooser`() {
        val chrome = SnakeChrome()

        // JsResult has a package-private constructor on Android; verify via dynamic proxy or null-safe path.
        var geoAllowed = true
        var geoRetained = true
        val geoCb = GeolocationPermissions.Callback { _, allow, retain ->
            geoAllowed = allow
            geoRetained = retain
        }
        chrome.onGeolocationPermissionsShowPrompt("https://example.org", geoCb)
        assertFalse(geoAllowed)
        assertFalse(geoRetained)

        var permDenied = false
        val permReq = object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse("https://example.org")
            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<out String>?) {}
            override fun deny() {
                permDenied = true
            }
        }
        chrome.onPermissionRequest(permReq)
        assertTrue(permDenied)

        var fileCallbackInvoked = false
        var fileValue: Array<Uri>? = arrayOf(Uri.parse("file:///x"))
        val fileCb = ValueCallback<Array<Uri>> { v ->
            fileCallbackInvoked = true
            fileValue = v
        }
        assertTrue(chrome.onShowFileChooser(null, fileCb, null))
        assertTrue(fileCallbackInvoked)
        assertNull(fileValue)

        assertFalse(chrome.onCreateWindow(null, isDialog = false, isUserGesture = true, resultMsg = null))
        assertTrue(chrome.onJsAlert(null, null, null, null))
        assertTrue(chrome.onJsConfirm(null, null, null, null))
        assertTrue(chrome.onJsPrompt(null, null, null, null, null))
        assertTrue(chrome.onJsBeforeUnload(null, null, null, null))

        var customShown = false
        var customHidden = false
        val videoChrome = SnakeChrome(
            onShowCustomViewCallback = { _, _ -> customShown = true },
            onHideCustomViewCallback = { customHidden = true }
        )
        videoChrome.onShowCustomView(null, null)
        videoChrome.onHideCustomView()
        assertTrue(customShown)
        assertTrue(customHidden)
    }

    @Test
    fun `SnakeClient passes blocked URL to onVpnBlockedCallback`() {
        val blockedUrls = mutableListOf<String?>()
        val client = SnakeClient(
            BlocklistEngine.fromRules(emptyList()),
            vpnKillSwitchEnabled = true,
            isVpnActive = { false },
            onVpnBlockedCallback = { blockedUrls.add(it) }
        )

        val mainResp = client.shouldInterceptRequest(null, fakeRequest("https://target.example/page", true))
        assertNotNull(mainResp)
        assertEquals(403, mainResp!!.statusCode)
        assertEquals(listOf("https://target.example/page"), blockedUrls)

        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("https://next.example/login", true)))
        assertEquals(listOf("https://target.example/page", "https://next.example/login"), blockedUrls)
    }

    @Test
    fun `SnakeClient delegates external schemes when allowed and handles renderer crash safely`() {
        val delegated = mutableListOf<String>()
        var crashHandled = false
        val client = SnakeClient(
            BlocklistEngine.fromRules(emptyList()),
            blockExternalSchemes = false,
            onExternalSchemeCallback = { uri ->
                delegated.add(uri.toString())
                true
            },
            onRenderProcessGoneCallback = {
                crashHandled = true
                true
            }
        )

        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("tg://resolve?domain=stealth", true)))
        assertEquals(listOf("tg://resolve?domain=stealth"), delegated)
        assertTrue(client.onRenderProcessGone(null, null))
        assertTrue(crashHandled)
        assertTrue(SnakeClient(BlocklistEngine.fromRules(emptyList())).onRenderProcessGone(null, null))
    }
}
