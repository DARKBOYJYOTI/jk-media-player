package com.jk.mediaplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun detectsNewerPatchMinorAndMajorVersions() {
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("v1.1.0", "1.0.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("v1.10.0", "1.2.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun ignoresSameOrOlderVersions() {
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.0.0", "v1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.0-beta.1", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("", "1.0.0"))
    }
}
