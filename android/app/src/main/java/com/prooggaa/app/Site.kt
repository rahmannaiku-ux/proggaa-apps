package com.prooggaa.app

import java.net.URI
import java.net.URLDecoder

/** Where the app points, and the small rules about links that are easy to get wrong. */
object Site {
    const val BASE = "https://prooggaa.com"
    const val START = "$BASE/dashboard"

    /** The update manifest of the newest GitHub Release (written by scripts/make-manifests.mjs). */
    const val UPDATE_MANIFEST =
        "https://github.com/rahmannaiku-ux/proggaa-apps/releases/latest/download/android-latest.json"

    /** A link the site shows (on /download, inside the app) to ask for an update check now. */
    const val CHECK_UPDATE_URL = "proggaa://update"

    /**
     * `proggaa://browser?url=<https address>`: the site asks for a page to open in
     * the person's normal browser (e.g. buying a mission, done on the website).
     * Returns that address, or null when the link isn't one of these.
     */
    fun browserTarget(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "proggaa" || uri.host != "browser") return null
        val raw = uri.rawQuery?.split("&")?.firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
        val target = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
        return if (runCatching { URI(target).scheme }.getOrNull() == "https") target else null
    }

    /** New, unread notifications for the signed-in person (see the website's /api/app/notifications). */
    const val NOTIFICATIONS_API = "$BASE/api/app/notifications"

    /** Whether a page should open inside the app; anything else goes to the browser or another app. */
    fun opensInApp(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        return host == "prooggaa.com" ||
            host.endsWith(".prooggaa.com") ||
            // Admins and mentors connecting Google Drive / Docs sign in to Google and come back.
            host == "accounts.google.com"
    }

    /**
     * The page a notification opens: its link when that is on the site
     * ("/community/..." or "https://prooggaa.com/..."), otherwise the dashboard.
     */
    fun pageFor(link: String?): String {
        if (link.isNullOrBlank()) return START
        if (link.startsWith("/") && !link.startsWith("//")) return BASE + link
        return if (opensInApp(link) && !link.contains("accounts.google.com")) link else START
    }

    /** 1.2.3 -> 10203: the same rule as versionCode in app/build.gradle.kts. */
    fun versionCode(version: String): Int? {
        val parts = version.trim().removePrefix("v").split(".")
        if (parts.size != 3) return null
        val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }
        if (minor > 99 || patch > 99) return null
        return major * 10000 + minor * 100 + patch
    }
}
