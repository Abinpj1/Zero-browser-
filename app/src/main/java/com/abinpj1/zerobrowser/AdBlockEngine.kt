package com.abinpj1.zerobrowser

import android.content.Context
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AdBlockEngine(private val context: Context) {

    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val cacheFile = File(context.filesDir, "oisd_rules.txt")
    private val prefs = context.getSharedPreferences("zero_prefs", Context.MODE_PRIVATE)

    val threatsBlocked = AtomicInteger(prefs.getInt("threats_blocked", 0))
    val popupsBlocked = AtomicInteger(prefs.getInt("popups_blocked", 0))

    val cosmeticCss = """
        javascript:(function(){
            var s=document.createElement('style');
            s.innerHTML='ins.adsbygoogle, [id^="div-gpt-ad"], .ad-banner, .ad-container, .adsbygoogle, iframe[src*="doubleclick"], iframe[src*="googlesyndication"], div[data-ad-slot] { display: none !important; height: 0 !important; }';
            document.head && document.head.appendChild(s);
        })();
    """.trimIndent()

    init {
        loadDefaultRules()
        loadCachedRules()
    }

    private fun loadDefaultRules() {
        listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "adservice.google.com", "taboola.com",
            "outbrain.com", "criteo.com", "adnxs.com", "pubmatic.com",
            "rubiconproject.com", "scorecardresearch.com", "popads.net",
            "propellerads.com", "adsterra.com", "exoclick.com", "mgid.com",
            "revcontent.com", "unityads.unity3d.com", "applovin.com"
        ).forEach { blockedDomains.add(it) }
    }

    private fun loadCachedRules() {
        if (!cacheFile.exists()) return
        try {
            cacheFile.bufferedReader().forEachLine { line ->
                val clean = line.trim().lowercase()
                if (clean.isNotEmpty() && !clean.startsWith("#") && !clean.startsWith("!")) {
                    blockedDomains.add(clean)
                }
            }
        } catch (_: Exception) {}
    }

    fun isBlocked(host: String): Boolean {
        var domain = host.lowercase()
        while (domain.contains(".")) {
            if (blockedDomains.contains(domain)) {
                val count = threatsBlocked.incrementAndGet()
                if (count % 5 == 0) prefs.edit().putInt("threats_blocked", count).apply()
                return true
            }
            domain = domain.substringAfter(".")
        }
        return false
    }

    fun recordPopupBlocked() {
        val count = popupsBlocked.incrementAndGet()
        prefs.edit().putInt("popups_blocked", count).apply()
    }

    fun ruleCount(): Int = blockedDomains.size

    fun lastUpdatedText(): String {
        val ts = prefs.getLong("last_filter_update", 0L)
        if (ts == 0L) return "Built-in baseline"
        val mins = (System.currentTimeMillis() - ts) / 60000L
        return if (mins < 1) "0 minutes ago" else "$mins minutes ago"
    }

    fun updateFiltersFromOisd(onComplete: (Boolean, Int) -> Unit) {
        Thread {
            try {
                // OISD Small/Big domain list (~100k-300k+ active ad/tracker domains)
                val conn = URL("https://small.oisd.nl/domainswild2").openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                }
                val newSet = HashSet<String>(150000)
                cacheFile.bufferedWriter().use { writer ->
                    conn.getInputStream().bufferedReader().forEachLine { raw ->
                        val line = raw.trim().lowercase()
                        if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                            newSet.add(line)
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
                if (newSet.size > 1000) {
                    blockedDomains.clear()
                    blockedDomains.addAll(newSet)
                    prefs.edit().putLong("last_filter_update", System.currentTimeMillis()).apply()
                    onComplete(true, blockedDomains.size)
                } else {
                    onComplete(false, blockedDomains.size)
                }
            } catch (_: Exception) {
                onComplete(false, blockedDomains.size)
            }
        }.start()
    }
}
