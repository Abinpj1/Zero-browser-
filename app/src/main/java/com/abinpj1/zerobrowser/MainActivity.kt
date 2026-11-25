package com.abinpj1.zerobrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var webViewContainer: FrameLayout
    private lateinit var btnTabs: Button

    data class BrowserTab(
        val webView: WebView,
        val isPrivate: Boolean = false,
        var title: String = "New Tab"
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var adBlockEnabled = true
    private var desktopMode = false
    private var forceDark = false

    private val defaultHomeUrl = "https://www.google.com"

    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "ads.yahoo.com",
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "taboola.com", "outbrain.com", "criteo.com", "popads.net", "propellerads.com",
        "zedo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        progressBar = findViewById(R.id.progressBar)
        webViewContainer = findViewById(R.id.webViewContainer)
        btnTabs = findViewById(R.id.btnTabs)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            val wv = currentWebView()
            if (wv.canGoBack()) wv.goBack()
        }

        findViewById<ImageButton>(R.id.btnReload).setOnClickListener {
            currentWebView().reload()
        }

        btnTabs.setOnClickListener { showTabsDialog() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenuDialog() }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadInput(urlInput.text.toString())
                hideKeyboard()
                true
            } else false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = currentWebView()
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })

        createNewTab(defaultHomeUrl, isPrivate = false)
    }

    private fun currentWebView(): WebView = tabs[activeTabIndex].webView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(url: String, isPrivate: Boolean) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = !isPrivate
                databaseEnabled = !isPrivate
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                if (desktopMode) {
                    userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                }
            }

            if (isPrivate) {
                CookieManager.getInstance().setAcceptCookie(false)
                clearHistory()
                clearCache(true)
            } else {
                CookieManager.getInstance().setAcceptCookie(true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (adBlockEnabled) {
                        val host = request?.url?.host?.lowercase() ?: ""
                        if (adHosts.any { host == it || host.endsWith(".$it") }) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (view == currentWebView() && !urlInput.hasFocus()) {
                        urlInput.setText(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val idx = tabs.indexOfFirst { it.webView == view }
                    if (idx != -1) {
                        tabs[idx].title = view?.title?.takeIf { it.isNotBlank() } ?: (url ?: "Tab")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == currentWebView()) {
                        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                        progressBar.progress = newProgress
                    }
                }
            }

            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this@MainActivity, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            }
        }

        tabs.add(BrowserTab(webView, isPrivate))
        switchToTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        activeTabIndex = index.coerceIn(0, tabs.lastIndex)
        webViewContainer.removeAllViews()
        val tab = tabs[activeTabIndex]
        webViewContainer.addView(tab.webView)
        urlInput.setText(tab.webView.url ?: "")
        urlInput.setBackgroundColor(if (tab.isPrivate) 0xFF3A1F4D.toInt() else 0xFF2C2C2C.toInt())
        btnTabs.text = tabs.size.toString()
    }

    private fun showTabsDialog() {
        val labels = tabs.mapIndexed { i, t ->
            val prefix = if (t.isPrivate) "[Private] " else ""
            val active = if (i == activeTabIndex) "● " else ""
            "$active$prefix${t.title.take(32)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(labels) { _, which ->
                if (tabs[which].isPrivate && which != activeTabIndex) {
                    authenticatePrivate { switchToTab(which) }
                } else {
                    switchToTab(which)
                }
            }
            .setPositiveButton("+ New Tab") { _, _ ->
                createNewTab(defaultHomeUrl, isPrivate = false)
            }
            .setNeutralButton("+ Private Tab") { _, _ ->
                authenticatePrivate { createNewTab(defaultHomeUrl, isPrivate = true) }
            }
            .setNegativeButton("Close Current") { _, _ ->
                if (tabs.size > 1) {
                    val removed = tabs.removeAt(activeTabIndex)
                    removed.webView.destroy()
                    switchToTab((activeTabIndex - 1).coerceAtLeast(0))
                }
            }
            .show()
    }

    private fun showMenuDialog() {
        val options = arrayOf(
            "Forward",
            "Ad-Blocker: ${if (adBlockEnabled) "ON" else "OFF"}",
            "Desktop Mode: ${if (desktopMode) "ON" else "OFF"}",
            "Force Dark Web: ${if (forceDark) "ON" else "OFF"}",
            "Clear Cache & Cookies"
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                val wv = currentWebView()
                when (which) {
                    0 -> if (wv.canGoForward()) wv.goForward()
                    1 -> {
                        adBlockEnabled = !adBlockEnabled
                        wv.reload()
                    }
                    2 -> {
                        desktopMode = !desktopMode
                        wv.settings.userAgentString = if (desktopMode) {
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                        } else null
                        wv.reload()
                    }
                    3 -> {
                        forceDark = !forceDark
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            @Suppress("DEPRECATION")
                            wv.settings.forceDark = if (forceDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                        }
                    }
                    4 -> {
                        wv.clearCache(true)
                        CookieManager.getInstance().removeAllCookies(null)
                        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun authenticatePrivate(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Tab")
            .setSubtitle("Verify fingerprint or screen lock")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private fun loadInput(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=${Uri.encode(text)}"
        }
        currentWebView().loadUrl(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
        urlInput.clearFocus()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
