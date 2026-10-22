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
    /** The site's "Notification settings" button: turn notifications on. */
    const val NOTIFICATIONS_URL = "proggaa://notifications"

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

    /** Counts for the tab bar badges (see the website's /api/app/badges). */
    const val BADGES_API = "$BASE/api/app/badges"

    /** New, unread notifications for the signed-in person (see the website's /api/app/notifications). */
    const val NOTIFICATIONS_API = "$BASE/api/app/notifications"

    /** Pages that belong on the website, not in the app (checking a certificate is done in the browser). */
    private val WEBSITE_ONLY = listOf("/certificates/verify")

    /** Whether a prooggaa.com address is one of the website-only pages, to open in the browser. */
    fun isWebsiteOnly(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "prooggaa.com" && !host.endsWith(".prooggaa.com")) return false
        val path = uri.path ?: return false
        return WEBSITE_ONLY.any { under(path, it) }
    }

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

    /** The app's tab bar: each tab is its own page stack, like a native app. */
    data class Tab(val label: String, val path: String)

    val TABS = listOf(
        Tab("Home", "/dashboard"),
        Tab("Learn", "/my-courses"),
        Tab("Exams", "/exams"),
        Tab("Community", "/community"),
        Tab("Chats", "/community/messages"),
    )

    private fun under(path: String, base: String) = path == base || path.startsWith("$base/")

    /** Which tab a page belongs to (a notification or link opens there). */
    fun tabFor(path: String): Int = when {
        under(path, "/community/messages") -> 4
        under(path, "/community") -> 3
        under(path, "/exams") || under(path, "/results") -> 2
        under(path, "/my-courses") || (under(path, "/missions") && path != "/missions") || under(path, "/challenges") -> 1
        else -> 0
    }

    private val SIGN_IN_PAGES = listOf("/login", "/register", "/forgot-password", "/sign-in", "/sign-up")

    /** The screens for signing in or up (back never returns to them once signed in). */
    fun isSignInPage(path: String?): Boolean = path != null && SIGN_IN_PAGES.any { under(path, it) }

    /** Signing in, and the mentor and admin consoles (they have their own navigation), show no tab bar. */
    fun showsTabBar(path: String): Boolean =
        !isSignInPage(path) && listOf("/complete-profile", "/admin", "/mentor", "/live/manage", "/security").none { under(path, it) }

    /** The path of a site address ("/community/u/x" for https://prooggaa.com/community/u/x?y=1), or null. */
    fun pathOf(url: String?): String? {
        val uri = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        return uri.path?.ifEmpty { "/" }
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
