package com.abinpj1.zerobrowser

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URL

class MainActivity : AppCompatActivity() {

    data class TabItem(
        val id: Long = System.nanoTime(),
        val webView: WebView,
        val isPrivate: Boolean = false,
        var title: String = "New Tab",
        var url: String = ""
    )

    private lateinit var adBlock: AdBlockEngine
    private lateinit var webContainer: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tabCountBtn: TextView
    private lateinit var engineBadge: TextView

    private val tabs = mutableListOf<TabItem>()
    private var activeIndex = 0
    private var lastTouchTime = 0L

    private val prefs by lazy { getSharedPreferences("zero_prefs", MODE_PRIVATE) }

    // Settings state
    private var adBlockOn
        get() = prefs.getBoolean("adblock", true)
        set(v) = prefs.edit().putBoolean("adblock", v).apply()

    private var popupBlockOn
        get() = prefs.getBoolean("popup_block", true)
        set(v) = prefs.edit().putBoolean("popup_block", v).apply()

    private var block3pCookies
        get() = prefs.getBoolean("block_3p_cookies", true)
        set(v) = prefs.edit().putBoolean("block_3p_cookies", v).apply()

    private var lockPrivateTabs
        get() = prefs.getBoolean("lock_private", false)
        set(v) = prefs.edit().putBoolean("lock_private", v).apply()

    private var searchEngine // "google" or "ddg"
        get() = prefs.getString("search_engine", "google") ?: "google"
        set(v) = prefs.edit().putString("search_engine", v).apply()

    private var isPrivateUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adBlock = AdBlockEngine(this)
        buildMainUi()

        val initialUrl = intent?.dataString ?: homeUrl()
        createNewTab(initialUrl, isPrivate = false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = currentTab()?.webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else if (tabs.size > 1) {
                    closeTab(activeIndex)
                } else {
                    finish()
                }
            }
        })
    }

    private fun homeUrl() = if (searchEngine == "google") "https://www.google.com" else "https://duckduckgo.com"

    private fun buildMainUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F4F7"))
        }

        // Top Floating Pill Address Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 24, 18)
            setBackgroundColor(Color.parseColor("#F2F4F7"))
        }

        val pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Color.WHITE, 50f)
            elevation = 4f
            setPadding(24, 8, 20, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        engineBadge = TextView(this).apply {
            text = if (searchEngine == "google") "G" else "D"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#EAECEF"), 30f)
            setPadding(22, 10, 22, 10)
            setOnClickListener {
                searchEngine = if (searchEngine == "google") "ddg" else "google"
                text = if (searchEngine == "google") "G" else "D"
                Toast.makeText(
                    this@MainActivity,
                    "Search: ${if (searchEngine == "google") "Google" else "DuckDuckGo"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        urlBar = EditText(this).apply {
            hint = "Search or enter URL"
            textSize = 15f
            setTextColor(Color.parseColor("#111111"))
            setHintTextColor(Color.parseColor("#8E9299"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(24, 16, 16, 16)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    loadInput(text.toString())
                    hideKeyboard()
                    true
                } else false
            }
        }

        val shieldQuickBtn = TextView(this).apply {
            text = "🛡"
            textSize = 16f
            setPadding(12, 8, 8, 8)
            setOnClickListener { openHyperSettingsDialog() }
        }

        pillContainer.addView(engineBadge)
        pillContainer.addView(urlBar)
        pillContainer.addView(shieldQuickBtn)
        topBar.addView(pillContainer)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6)
            progressTintList = ColorStateList.valueOf(Color.parseColor("#24C16B"))
            max = 100
        }

        webContainer = FrameLayout(this)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setColorSchemeColors(Color.parseColor("#24C16B"))
            setOnRefreshListener {
                currentTab()?.webView?.reload()
                isRefreshing = false
            }
            addView(webContainer)
        }

        // Bottom Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            elevation = 12f
            setPadding(16, 14, 16, 14)
        }

        fun navIcon(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 12, 0, 12)
            setOnClickListener { onClick() }
        }

        tabCountBtn = TextView(this).apply {
            text = "1"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            background = roundBorderBg(Color.TRANSPARENT, Color.parseColor("#111111"), 16f, 4)
            layoutParams = LinearLayout.LayoutParams(72, 72)
            setOnClickListener { openTabSwitcherDialog() }
        }

        val tabWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(tabCountBtn, FrameLayout.LayoutParams(72, 72, Gravity.CENTER))
        }

        bottomBar.addView(navIcon("‹") { currentTab()?.webView?.takeIf { it.canGoBack() }?.goBack() })
        bottomBar.addView(navIcon("›") { currentTab()?.webView?.takeIf { it.canGoForward() }?.goForward() })
        bottomBar.addView(navIcon("⌂") { currentTab()?.webView?.loadUrl(homeUrl()) })
        bottomBar.addView(tabWrapper)
        bottomBar.addView(navIcon("☰") { openHyperSettingsDialog() })

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(swipeRefresh)
        root.addView(bottomBar)
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createNewTab(url: String, isPrivate: Boolean) {
        val wv = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) lastTouchTime = System.currentTimeMillis()
                false
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = !isPrivate
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                if (isPrivate) cacheMode = WebSettings.LOAD_NO_CACHE
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !block3pCookies)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (currentTab()?.webView == view) {
                        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                        progressBar.progress = newProgress
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    tabs.find { it.webView == view }?.title = title ?: "Tab"
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (popupBlockOn) {
                        adBlock.recordPopupBlocked()
                        return false
                    }
                    return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                    val tab = tabs.find { it.webView == view }
                    tab?.url = pageUrl ?: ""
                    if (currentTab()?.webView == view && !urlBar.hasFocus()) {
                        urlBar.setText(if (isPrivate) "🕶 ${pageUrl ?: ""}" else (pageUrl ?: ""))
                    }
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (adBlockOn) view?.evaluateJavascript(adBlock.cosmeticCss, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val targetUri = request?.url ?: return false
                    val scheme = targetUri.scheme?.lowercase() ?: return false

                    // Block non-http external intent hijacks unless tapped recently
                    if (scheme != "http" && scheme != "https") {
                        if (popupBlockOn && System.currentTimeMillis() - lastTouchTime > 1200) {
                            adBlock.recordPopupBlocked()
                            return true
                        }
                        return try {
                            startActivity(Intent(Intent.ACTION_VIEW, targetUri))
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }

                    // Block automatic cross-domain popup/ad redirects without user gesture
                    if (popupBlockOn && !request.hasGesture() && System.currentTimeMillis() - lastTouchTime > 1200) {
                        val currentHost = Uri.parse(view?.url ?: "").host ?: ""
                        val newHost = targetUri.host ?: ""
                        if (currentHost.isNotEmpty() && newHost != currentHost && adBlock.isBlocked(newHost)) {
                            adBlock.recordPopupBlocked()
                            return true
                        }
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (adBlockOn) {
                        val host = request?.url?.host ?: return null
                        if (adBlock.isBlocked(host)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        val tab = TabItem(webView = wv, isPrivate = isPrivate, url = url)
        tabs.add(tab)
        switchToTab(tabs.lastIndex)
        wv.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        val target = tabs[index]
        if (target.isPrivate && lockPrivateTabs && !isPrivateUnlocked) {
            authenticatePrivate {
                isPrivateUnlocked = true
                switchToTab(index)
            }
            return
        }
        activeIndex = index
        webContainer.removeAllViews()
        webContainer.addView(
            target.webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        window.setFlags(
            if (target.isPrivate) WindowManager.LayoutParams.FLAG_SECURE else 0,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        urlBar.setText(if (target.isPrivate) "🕶 ${target.url}" else target.url)
        tabCountBtn.text = tabs.size.toString()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            createNewTab(homeUrl(), isPrivate = false)
        } else {
            switchToTab((index - 1).coerceAtLeast(0))
        }
    }

    private fun currentTab(): TabItem? = tabs.getOrNull(activeIndex)

    private fun loadInput(raw: String) {
        val clean = raw.removePrefix("🕶 ").trim()
        val url = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.contains(".") && !clean.contains(" ") -> "https://$clean"
            searchEngine == "google" -> "https://www.google.com/search?q=${Uri.encode(clean)}"
            else -> "https://duckduckgo.com/?q=${Uri.encode(clean)}"
        }
        currentTab()?.webView?.loadUrl(url)
    }

    // HyperOS / MIUI-Style Privacy & Settings Dialog
    private fun openHyperSettingsDialog() {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F4F7"))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 60)
        }

        // Top Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32)
        }
        val backBtn = TextView(this).apply {
            text = "‹"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            background = roundBg(Color.WHITE, 45f)
            layoutParams = LinearLayout.LayoutParams(96, 96)
            setOnClickListener { dialog.dismiss() }
        }
        val titleTv = TextView(this).apply {
            text = "Privacy & Settings"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(96, 96) }
        header.addView(backBtn)
        header.addView(titleTv)
        header.addView(spacer)
        page.addView(header)

        fun sectionHeader(label: String) {
            page.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#7A808A"))
                letterSpacing = 0.08f
                setPadding(24, 28, 0, 14)
            })
        }

        fun hyperCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.WHITE, 44f)
            setPadding(36, 12, 36, 12)
        }

        fun toggleRow(title: String, initial: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 26, 0, 26)
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#141414"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(this).apply {
                isChecked = initial
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.parseColor("#24C16B"), Color.parseColor("#D5D9E0"))
                )
                setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            }
            row.addView(tv)
            row.addView(sw)
            return row
        }

        fun valueRow(title: String, value: String, onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 26, 0, 26)
                if (onClick != null) setOnClickListener { onClick() }
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#141414"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valTv = TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(Color.parseColor("#6E737D"))
            }
            row.addView(tv)
            row.addView(valTv)
            return Pair(row, valTv)
        }

        // BLOCKING CARD
        sectionHeader("BLOCKING")
        val blockingCard = hyperCard().apply {
            addView(toggleRow("Adblock", adBlockOn) {
                adBlockOn = it
                currentTab()?.webView?.reload()
            })
            addView(toggleRow("Popup & redirect blocker", popupBlockOn) { popupBlockOn = it })
            addView(toggleRow("Block 3rd-party cookies", block3pCookies) { enabled ->
                block3pCookies = enabled
                tabs.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it.webView, !enabled) }
            })
        }
        page.addView(blockingCard)

        // SECURITY & SEARCH CARD
        sectionHeader("SECURITY & SEARCH")
        val secCard = hyperCard().apply {
            addView(toggleRow("Lock private tabs", lockPrivateTabs) { lockPrivateTabs = it })
            lateinit var engineVal: TextView
            val (engineRow, tv) = valueRow("Search engine", if (searchEngine == "google") "Google" else "DuckDuckGo") {
                searchEngine = if (searchEngine == "google") "ddg" else "google"
                val label = if (searchEngine == "google") "Google" else "DuckDuckGo"
                engineVal.text = label
                engineBadge.text = if (searchEngine == "google") "G" else "D"
            }
            engineVal = tv
            addView(engineRow)
        }
        page.addView(secCard)

        // FILTERS CARD
        sectionHeader("FILTERS")
        lateinit var ruleValTv: TextView
        lateinit var updatedValTv: TextView
        val filtersCard = hyperCard().apply {
            addView(valueRow("Threats blocked", adBlock.threatsBlocked.get().toString()).first)
            addView(valueRow("Popups & redirects", adBlock.popupsBlocked.get().toString()).first)
            val (rRow, rTv) = valueRow("Filter rules", adBlock.ruleCount().toString())
            ruleValTv = rTv
            addView(rRow)

            val (uRow, uTv) = valueRow("Update now", adBlock.lastUpdatedText()) {
                updatedValTv.text = "Updating..."
                adBlock.updateFiltersFromOisd { ok, count ->
                    runOnUiThread {
                        ruleValTv.text = count.toString()
                        updatedValTv.text = if (ok) "0 minutes ago" else "Failed (check network)"
                    }
                }
            }
            updatedValTv = uTv
            addView(uRow)
        }
        page.addView(filtersCard)

        // DATA CARD
        sectionHeader("DATA")
        val dataCard = hyperCard().apply {
            addView(valueRow("Clear cache", "") {
                tabs.forEach { it.webView.clearCache(true) }
                Toast.makeText(this@MainActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
            }.first)
            addView(valueRow("Clear cookies & site data", "") {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                Toast.makeText(this@MainActivity, "Cookies & site data cleared", Toast.LENGTH_SHORT).show()
            }.first)
            val clearAll = TextView(this@MainActivity).apply {
                text = "Clear browsing data"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E53935"))
                setPadding(0, 26, 0, 26)
                setOnClickListener {
                    tabs.forEach {
                        it.webView.clearCache(true)
                        it.webView.clearHistory()
                    }
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this@MainActivity, "All browsing data cleared", Toast.LENGTH_SHORT).show()
                }
            }
            addView(clearAll)
        }
        page.addView(dataCard)

        // ABOUT & UPDATES CARD
        sectionHeader("ABOUT")
        lateinit var updateStatusTv: TextView
        val aboutCard = hyperCard().apply {
            addView(valueRow("Browser", "Zero Browser v1.0.0").first)
            addView(valueRow("Developer", "GUM SEI").first)
            addView(valueRow("GitHub", "github.com/Abinpj1") {
                dialog.dismiss()
                currentTab()?.webView?.loadUrl("https://github.com/Abinpj1")
            }.first)
            val (updRow, updTv) = valueRow("Check for updates", "Tap to check") {
                updateStatusTv.text = "Checking..."
                checkGithubUpdates { msg, releaseUrl ->
                    runOnUiThread {
                        updateStatusTv.text = msg
                        if (releaseUrl != null) {
                            dialog.dismiss()
                            currentTab()?.webView?.loadUrl(releaseUrl)
                        }
                    }
                }
            }
            updateStatusTv = updTv
            addView(updRow)
        }
        page.addView(aboutCard)

        scroll.addView(page)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun openTabSwitcherDialog() {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.parseColor("#F2F4F7"), 44f)
            setPadding(36, 36, 36, 36)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        val title = TextView(this).apply {
            text = "Tabs (${tabs.size})"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val newTabBtn = TextView(this).apply {
            text = "+ New"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#111111"), 32f)
            setPadding(28, 14, 28, 14)
            setOnClickListener {
                dialog.dismiss()
                createNewTab(homeUrl(), isPrivate = false)
            }
        }
        val privateBtn = TextView(this).apply {
            text = "🕶 Private"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            background = roundBg(Color.WHITE, 32f)
            setPadding(28, 14, 28, 14)
            setOnClickListener {
                dialog.dismiss()
                createNewTab(homeUrl(), isPrivate = true)
            }
        }
        topRow.addView(title)
        topRow.addView(privateBtn)
        topRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        topRow.addView(newTabBtn)
        container.addView(topRow)

        tabs.forEachIndexed { idx, tab ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundBg(if (tab.isPrivate) Color.parseColor("#1E1E1E") else Color.WHITE, 32f)
                setPadding(32, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                setOnClickListener {
                    dialog.dismiss()
                    switchToTab(idx)
                }
            }
            val label = TextView(this).apply {
                text = (if (tab.isPrivate) "🕶 " else "") + tab.title
                textSize = 15f
                maxLines = 1
                setTypeface(null, if (idx == activeIndex) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (tab.isPrivate) Color.WHITE else Color.parseColor("#111111"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val close = TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.GRAY)
                setPadding(24, 8, 12, 8)
                setOnClickListener {
                    dialog.dismiss()
                    closeTab(idx)
                }
            }
            card.addView(label)
            card.addView(close)
            container.addView(card)
        }

        dialog.setContentView(container)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun authenticatePrivate(onSuccess: () -> Unit) {
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Tabs")
            .setSubtitle("Verify phone lock to access private tabs")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private fun checkGithubUpdates(onResult: (String, String?) -> Unit) {
        Thread {
            try {
                val jsonText = URL("https://api.github.com/repos/Abinpj1/Zero-browser-/releases/latest").readText()
                val obj = JSONObject(jsonText)
                val tag = obj.optString("tag_name", "v1.0.0")
                val htmlUrl = obj.optString("html_url", "https://github.com/Abinpj1/Zero-browser-/releases")
                if (tag != "v1.0.0" && tag != "1.0.0") {
                    onResult("New: $tag (Opening...)", htmlUrl)
                } else {
                    onResult("Up to date (v1.0.0)", null)
                }
            } catch (_: Exception) {
                onResult("No releases published yet", null)
            }
        }.start()
    }

    private fun roundBg(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun roundBorderBg(fill: Int, stroke: Int, radius: Float, width: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        setStroke(width, stroke)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }
}
