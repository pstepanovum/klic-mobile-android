package com.klic.mobile.app.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterUrlTest {

    private fun allowed(url: String) = AppUpdater.isAllowedDownloadUrl(url.toHttpUrl())

    @Test
    fun allowsGithubReleaseHostsOverHttps() {
        assertTrue(allowed("https://github.com/pstepanovum/klic-mobile-android/releases/download/v1/app.apk"))
        assertTrue(allowed("https://objects.githubusercontent.com/github-production-release-asset/1/2"))
        assertTrue(allowed("https://release-assets.githubusercontent.com/x"))
        assertTrue(allowed("https://GitHub.com/x"))
    }

    @Test
    fun rejectsOtherSchemesAndHosts() {
        assertFalse(allowed("http://github.com/x.apk"))
        assertFalse(allowed("http://objects.githubusercontent.com/x"))
        assertFalse(allowed("https://evil.com/x.apk"))
        assertFalse(allowed("https://github.com.evil.com/x.apk"))
        assertFalse(allowed("https://githubusercontent.com.evil.com/x"))
        assertFalse(allowed("https://evilgithubusercontent.com/x"))
        assertFalse(allowed("https://api.github.com/x"))
    }
}
