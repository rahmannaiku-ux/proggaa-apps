package com.prooggaa.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app: prooggaa.com shown in native tabs. Each tab of the bottom bar
 * (Home, Learn, Exams, Community, Chats) keeps its own page and history,
 * like a real app, with unread badges. Around the pages: a splash screen,
 * bars coloured like the page, file and video picking for uploads,
 * downloads, full-screen video, the back button, an offline screen,
 * opening notifications in the right tab, and updates. The site gets no
 * JavaScript bridge into the app.
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabBar: TabBar
    private lateinit var progress: ProgressBar
    private lateinit var offline: View
    private lateinit var updater: AppUpdater

    /** One web view per tab, made the first time the tab is opened. */
    private val tabs = arrayOfNulls<WebView>(Site.TABS.size)
    private var current = 0
    private var keyboardOpen = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var mainFrameFailed = false
    private var badgeJob: Job? = null
    private var lastBadgeCheck = 0L

    /** The splash screen stays up until the first page has drawn (or a few seconds pass). */
    private var firstPageShown = false
    private val startedAt = SystemClock.uptimeMillis()
    private val showProgress = Runnable { progress.isVisible = true }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            !firstPageShown && SystemClock.uptimeMillis() - startedAt < SPLASH_MAX_MS
        }
        super.onCreate(savedInstanceState)
        // Draw edge to edge and pad for the status bar, navigation bar and keyboard ourselves.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setBarsColor(ContextCompat.getColor(this, R.color.proggaa_bg))

        val background = ContextCompat.getColor(this, R.color.proggaa_bg)
        root = FrameLayout(this).apply { setBackgroundColor(background) }
        content = FrameLayout(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isVisible = false
            progressTintList = ContextCompat.getColorStateList(context, R.color.proggaa_accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        }
        offline = buildOfflineView()
        tabBar = TabBar(this) { index, reselected -> onTabSelected(index, reselected) }.apply { isVisible = false }

        content.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))
        content.addView(offline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Like other apps, the tab bar steps aside while typing.
            keyboardOpen = ime.bottom > bars.bottom
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            updateTabBarVisibility()
            WindowInsetsCompat.CONSUMED
        }

        CookieManager.getInstance().setAcceptCookie(true)
        updater = AppUpdater(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = tabs[current]
                when {
                    fullscreenView != null -> exitFullscreen()
                    web != null && web.canGoBack() && !offline.isVisible -> web.goBack()
                    current != 0 && tabBar.isVisible -> showTab(0)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        val restoredTab = savedInstanceState?.getInt(KEY_CURRENT, -1) ?: -1
        if (restoredTab >= 0) {
            for (i in tabs.indices) {
                val state = savedInstanceState?.getBundle("$KEY_TAB$i") ?: continue
                tabFor(i, initialUrl = null).restoreState(state)
            }
            showTab(restoredTab, animate = false)
        } else {
            val page = pageFromIntent(intent)
            val start = page?.let { Site.tabFor(Site.pathOf(it) ?: "/") } ?: 0
            tabFor(start, initialUrl = page ?: Site.START)
            showTab(start, animate = false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // (The automatic update check runs in onResume, every time the app comes to the front.)
        if (intent?.getBooleanExtra(EXTRA_CHECK_UPDATE, false) == true) updater.check(manual = true)
    }

    // ---------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------

    private fun tabRoot(index: Int) = Site.BASE + Site.TABS[index].path

    /** The web view of a tab, made (and pointed at `initialUrl` or the tab's first page) when first needed. */
    private fun tabFor(index: Int, initialUrl: String? = tabRoot(index)): WebView {
        tabs[index]?.let { return it }
        val web = newWebView()
        // INVISIBLE, not GONE: a hidden tab keeps the full screen size while its page loads,
        // so the page is laid out for the phone's width instead of shrunk to a desktop width.
        web.visibility = View.INVISIBLE
        content.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs[index] = web
        initialUrl?.let { web.loadUrl(it) }
        return web
    }

    private fun showTab(index: Int, animate: Boolean = true) {
        val next = tabFor(index)
        val previous = tabs[current]
        current = index
        tabBar.select(index)
        previous?.animate()?.cancel()
        next.animate().cancel()
        next.visibility = View.VISIBLE
        if (previous !== next) previous?.visibility = View.INVISIBLE
        if (animate && previous !== next) {
            // A quick fade-in, like switching tabs in a native app.
            next.alpha = 0.35f
            next.animate().alpha(1f).setDuration(140).start()
        } else {
            next.alpha = 1f
        }
        next.requestFocus()
        progress.isVisible = false
        offline.isVisible = false
        updateTabBarVisibility()
        matchBarsToPage()
    }

    private fun onTabSelected(index: Int, reselected: Boolean) {
        if (!reselected) {
            showTab(index)
            return
        }
        // Tapping the open tab again: back to its first page, or to the top of it.
        val web = tabs[index] ?: return
        if (Site.pathOf(web.url) == Site.TABS[index].path) {
            web.evaluateJavascript("window.scrollTo({top:0,behavior:'smooth'})", null)
        } else {
            web.loadUrl(tabRoot(index))
        }
    }

    private fun updateTabBarVisibility() {
        if (!::tabBar.isInitialized) return
        val path = Site.pathOf(tabs[current]?.url)
        tabBar.isVisible = !keyboardOpen && path != null && Site.showsTabBar(path) && fullscreenView == null
    }

    /** Signed out: the other tabs show an old session's pages, so they start again after the next sign-in. */
    private fun forgetOtherTabs() {
        for (i in tabs.indices) {
            if (i == current) continue
            tabs[i]?.let {
                content.removeView(it)
                it.destroy()
            }
            tabs[i] = null
        }
        for (i in tabs.indices) tabBar.setBadge(i, 0)
    }

    // ---------------------------------------------------------------------
    // Web views
    // ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val web = WebView(this)
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.proggaa_bg))
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) // YouTube lesson videos
        // The site scrolls itself; no Android glow at the edges of the page.
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.settings.apply {
            // Lay pages out for the phone like a mobile browser does, never zoomed out,
            // and keep the app's text size steady whatever the phone's font setting.
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // reels start muted on their own
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = true // picking files to upload
            userAgentString = "$userAgentString ProggaaApp/Android/${BuildConfig.VERSION_NAME}"
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                Site.browserTarget(url)?.let {
                    openInBrowser(it)
                    return true
                }
                if (url.startsWith(Site.CHECK_UPDATE_URL)) {
                    updater.check(manual = true)
                    return true
                }
                if (!request.isForMainFrame || Site.opensInApp(url)) return false
                openOutside(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== tabs[current]) return
                mainFrameFailed = false
                // Only slow loads show the bar: a quick page change should feel instant, like an app.
                progress.removeCallbacks(showProgress)
                progress.postDelayed(showProgress, PROGRESS_DELAY_MS)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (view !== tabs[current]) return
                progress.removeCallbacks(showProgress)
                progress.isVisible = false
                if (!mainFrameFailed) offline.isVisible = false
                firstPageShown = true
                pageChanged(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                // The site changes pages without a full load.
                if (view === tabs[current]) view.postDelayed({ pageChanged(view.url) }, 300)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && view === tabs[current]) {
                    mainFrameFailed = true
                    firstPageShown = true
                    offline.isVisible = true
                    setBarsColor(ContextCompat.getColor(this@MainActivity, R.color.proggaa_bg))
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // The web view's process crashed or was killed for memory: start fresh instead of crashing.
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === tabs[current]) progress.progress = newProgress
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFiles.launch(fileChooserIntent(params))
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() = exitFullscreen()
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
        return web
    }

    /** After every page change in the open tab: tab bar, bar colours, badges, and sign-out. */
    private fun pageChanged(url: String?) {
        val path = Site.pathOf(url)
        if (path == "/login" || path == "/register") forgetOtherTabs()
        updateTabBarVisibility()
        matchBarsToPage()
        if (tabBar.isVisible && SystemClock.uptimeMillis() - lastBadgeCheck > 20_000) refreshBadges()
    }

    // ---------------------------------------------------------------------
    // Badges
    // ---------------------------------------------------------------------

    /** Reads the unread counts for the tab bar (signed in only). */
    private fun refreshBadges() {
        lastBadgeCheck = SystemClock.uptimeMillis()
        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val cookies = CookieManager.getInstance().getCookie(Site.BASE) ?: return@withContext null
                if (!cookies.contains("proggaa_session=")) return@withContext null
                runCatching {
                    val conn = (URL(Site.BADGES_API).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        setRequestProperty("Cookie", cookies)
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        if (conn.responseCode != 200) null else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            } ?: return@launch
            tabBar.setBadge(Site.TABS.indexOfFirst { it.label == "Chats" }, counts.optInt("chats"))
        }
    }

    private fun startBadgeLoop() {
        badgeJob?.cancel()
        badgeJob = lifecycleScope.launch {
            while (isActive) {
                if (tabBar.isVisible) refreshBadges()
                delay(BADGE_EVERY_MS)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Files, downloads, links, full screen
    // ---------------------------------------------------------------------

    /** Opens the system file picker with the types the page asked for (e.g. images, or the reel video formats). */
    private fun fileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val mimeTypes = params.acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { type ->
                if (type.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1)) else type
            }
            .distinct()
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(this, "This file can't be saved from the app. Open the page in a browser to save it.", Toast.LENGTH_LONG).show()
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            // Older Android needs a storage permission for the shared Downloads folder; the app's own folder doesn't.
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
        }
        runCatching { getSystemService(DownloadManager::class.java).enqueue(request) }
            .onSuccess { Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Couldn't start the download.", Toast.LENGTH_LONG).show() }
    }

    private fun openOutside(url: String) {
        val intent = if (url.startsWith("intent:")) {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                // Never let a page open one of this app's own screens or grant file access.
                component = null
                selector = null
                flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv()
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } ?: return
        try {
            startActivity(intent.addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens a prooggaa.com page in the phone's browser. The selector makes Android
     * pick a browser, not this app (which also opens prooggaa.com links).
     */
    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open prooggaa.com in your browser to do this.", Toast.LENGTH_LONG).show()
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as FrameLayout).removeView(view)
        fullscreenView = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        updateTabBarVisibility()
    }

    // ---------------------------------------------------------------------
    // Colours
    // ---------------------------------------------------------------------

    /**
     * Colours the status bar, navigation bar and tab bar like the page (cream,
     * or dark when the site is in dark mode), so the app looks all of a piece.
     */
    private fun matchBarsToPage() {
        val web = tabs[current] ?: return
        web.evaluateJavascript(
            "(function(){var e=document.querySelector('header')||document.body;" +
                "var c=getComputedStyle(e).backgroundColor;" +
                "if(!c||c==='rgba(0, 0, 0, 0)'||c==='transparent')c=getComputedStyle(document.body).backgroundColor;return c})()"
        ) { raw ->
            val match = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)""").find(raw ?: return@evaluateJavascript) ?: return@evaluateJavascript
            val (r, g, b) = match.destructured
            val color = Color.rgb(r.toInt(), g.toInt(), b.toInt())
            setBarsColor(color)
            tabBar.setPageColor(color)
        }
    }

    private fun setBarsColor(color: Int) {
        if (::root.isInitialized) root.setBackgroundColor(color)
        val light = ColorUtils.calculateLuminance(color) > 0.5
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun buildOfflineView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isVisible = false
        isClickable = true // nothing behind it can be tapped
        setBackgroundColor(ContextCompat.getColor(context, R.color.proggaa_bg))
        setPadding(dp(32), dp(32), dp(32), dp(32))
        addView(ImageView(context).apply { setImageResource(R.drawable.proggaa_logo) }, LinearLayout.LayoutParams(dp(112), dp(112)))
        addView(TextView(context).apply {
            text = "You're offline"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.proggaa_ink))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        addView(TextView(context).apply {
            text = "Proggaa needs the internet. Check your connection and try again."
            setTextColor(ContextCompat.getColor(context, R.color.proggaa_muted_ink))
            gravity = Gravity.CENTER
        })
        addView(Button(context).apply {
            text = "Try again"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.proggaa_primary)
            setOnClickListener {
                val web = tabs[current] ?: return@setOnClickListener
                if (web.url != null) web.reload() else web.loadUrl(tabRoot(current))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /** The page an intent asks for: a notification's link, or a prooggaa.com link opened with the app. */
    private fun pageFromIntent(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_URL)?.let { return Site.pageFor(it) }
        val data = intent.dataString ?: return null
        return if (intent.action == Intent.ACTION_VIEW && Site.opensInApp(data)) data else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) {
            updater.check(manual = true)
            return
        }
        val page = pageFromIntent(intent) ?: return
        val index = Site.tabFor(Site.pathOf(page) ?: "/")
        val web = tabFor(index, initialUrl = null)
        web.loadUrl(page)
        showTab(index)
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        tabs.forEach { it?.onResume() }
        updater.onResume()
        updater.check(manual = false)
        startBadgeLoop()
    }

    override fun onPause() {
        inForeground = false
        badgeJob?.cancel()
        tabs.forEach { it?.onPause() }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT, current)
        tabs.forEachIndexed { i, web -> web?.let { outState.putBundle("$KEY_TAB$i", Bundle().also { b -> it.saveState(b) }) } }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        tabs.forEach {
            it?.let { web ->
                content.removeView(web)
                web.destroy()
            }
        }
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "url"
        /** Set by the "update available" notification: open the update prompt. */
        const val EXTRA_CHECK_UPDATE = "checkUpdate"
        private const val KEY_CURRENT = "tab"
        private const val KEY_TAB = "tab-state-"
        private const val SPLASH_MAX_MS = 4000L
        private const val PROGRESS_DELAY_MS = 350L
        private const val BADGE_EVERY_MS = 60_000L

        /** Read by NotificationWorker: while the app is open the site shows notifications itself. */
        @Volatile
        var inForeground = false
    }
}
