package com.prooggaa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteTest {
    @Test
    fun keepsTheSiteInTheApp() {
        assertTrue(Site.opensInApp("https://prooggaa.com/community"))
        assertTrue(Site.opensInApp("https://www.prooggaa.com/"))
        assertTrue(Site.opensInApp("https://accounts.google.com/o/oauth2/auth"))
    }

    @Test
    fun sendsOtherLinksOut() {
        assertFalse(Site.opensInApp("https://www.youtube.com/watch?v=x"))
        assertFalse(Site.opensInApp("https://prooggaa.com.evil.example/"))
        assertFalse(Site.opensInApp("https://evilprooggaa.com/"))
        assertFalse(Site.opensInApp("mailto:hi@prooggaa.com"))
        assertFalse(Site.opensInApp("proggaa://update"))
        assertFalse(Site.opensInApp("not a url"))
    }

    @Test
    fun notificationLinksStayOnTheSite() {
        assertEquals("https://prooggaa.com/community/messages/g/1", Site.pageFor("/community/messages/g/1"))
        assertEquals("https://prooggaa.com/medals", Site.pageFor("https://prooggaa.com/medals"))
        assertEquals(Site.START, Site.pageFor(null))
        assertEquals(Site.START, Site.pageFor("//evil.example/x"))
        assertEquals(Site.START, Site.pageFor("https://evil.example/x"))
        assertEquals(Site.START, Site.pageFor("javascript:alert(1)"))
    }

    @Test
    fun websiteOnlyPagesOpenInTheBrowser() {
        assertEquals(
            "https://prooggaa.com/courses/full-stack",
            Site.browserTarget("proggaa://browser?url=https%3A%2F%2Fprooggaa.com%2Fcourses%2Ffull-stack"),
        )
        assertNull(Site.browserTarget("proggaa://browser?url=javascript%3Aalert(1)"))
        assertNull(Site.browserTarget("proggaa://browser?url=intent%3A%2F%2Fx"))
        assertNull(Site.browserTarget("proggaa://update"))
        assertNull(Site.browserTarget("https://prooggaa.com/"))
    }

    @Test
    fun versionCodes() {
        assertEquals(10000, Site.versionCode("1.0.0"))
        assertEquals(10203, Site.versionCode("v1.2.3"))
        assertNull(Site.versionCode("1.2"))
        assertNull(Site.versionCode("1.100.0"))
        assertNull(Site.versionCode("1.x.0"))
    }
}
