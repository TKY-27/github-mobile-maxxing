package dev.maxxing.github.app

import dev.maxxing.github.app.ui.formatAppBuildFooter
import dev.maxxing.github.model.AppBranding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppBrandingTest {
    @Test
    fun appBrandingUsesGithubMobileMaxxingIdentity() {
        assertEquals("github-mobile-maxxing", AppBranding.APP_NAME)
        assertEquals("github-mobile-maxxing-KMP/1.0", AppBranding.USER_AGENT)
        assertEquals("Ov23lihCAIZTj1Puaf9r", AppBranding.DEFAULT_OAUTH_CLIENT_ID)
        assertEquals("Ov23lihCAIZTj1Puaf9r", MaxxingBuildConfig.DEFAULT_GITHUB_CLIENT_ID)
    }

    @Test
    fun buildConfigAndSettingsFooterFormatVersionAndCommit() {
        assertTrue(MaxxingBuildConfig.APP_VERSION_NAME.isNotBlank())
        assertTrue(MaxxingBuildConfig.APP_COMMIT_SHA.isNotBlank())
        assertNotEquals("unknown", MaxxingBuildConfig.APP_COMMIT_SHA)

        assertEquals("v1.0.5 (abc1234)", formatAppBuildFooter("1.0.5", "abc1234"))
        assertEquals("v1.0.62-nightly.ef36892 (ef36892)", formatAppBuildFooter("v1.0.62-nightly.ef36892", "ef36892"))
        assertEquals("vdev (unknown)", formatAppBuildFooter("", ""))
    }
}
