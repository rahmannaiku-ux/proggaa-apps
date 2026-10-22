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
    fun pagesOpenInTheirTab() {
        assertEquals(0, Site.tabFor("/dashboard"))
        assertEquals(1, Site.tabFor("/my-courses"))
        assertEquals(1, Site.tabFor("/missions/abc/operations/x"))
        assertEquals(0, Site.tabFor("/missions"))
        assertEquals(2, Site.tabFor("/results/123"))
        assertEquals(3, Site.tabFor("/community/u/naimur"))
        assertEquals(4, Site.tabFor("/community/messages/g/1"))
        assertEquals(0, Site.tabFor("/notifications"))
    }

    @Test
    fun tabBarHidesWhereItDoesNotBelong() {
        assertTrue(Site.showsTabBar("/dashboard"))
        assertTrue(Site.showsTabBar("/community"))
        assertFalse(Site.showsTabBar("/login"))
        assertFalse(Site.showsTabBar("/admin/apps"))
        assertFalse(Site.showsTabBar("/mentor/dashboard"))
        assertTrue(Site.showsTabBar("/mentorship")) // only whole path segments count
    }

    @Test
    fun websiteOnlyPages() {
        assertTrue(Site.isWebsiteOnly("https://prooggaa.com/certificates/verify/PRG-AAAA-BBBB-CCCC"))
        assertTrue(Site.isWebsiteOnly("https://www.prooggaa.com/certificates/verify?id=x"))
        assertFalse(Site.isWebsiteOnly("https://prooggaa.com/certificates/verifying"))
        assertFalse(Site.isWebsiteOnly("https://prooggaa.com/medals"))
        assertFalse(Site.isWebsiteOnly("https://evil.example/certificates/verify"))
        assertFalse(Site.isWebsiteOnly(null))
    }

    @Test
    fun signInPages() {
        assertTrue(Site.isSignInPage("/login"))
        assertTrue(Site.isSignInPage("/register/verify"))
        assertFalse(Site.isSignInPage("/dashboard"))
        assertFalse(Site.isSignInPage("/loginhelp"))
        assertFalse(Site.isSignInPage(null))
    }

    @Test
    fun pathsOfAddresses() {
        assertEquals("/community/u/x", Site.pathOf("https://prooggaa.com/community/u/x?y=1"))
        assertEquals("/", Site.pathOf("https://prooggaa.com"))
        assertNull(Site.pathOf(null))
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
