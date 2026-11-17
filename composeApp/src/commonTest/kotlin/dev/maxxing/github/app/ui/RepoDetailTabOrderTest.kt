package dev.maxxing.github.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RepoDetailTabOrderTest {
    @Test
    fun `repo detail tabs start with Code Issues PRs and omit standalone Commits tab`() {
        val labels = RepoDetailTab.entries.map { it.label }
        assertEquals(
            listOf("Code", "Issues", "PRs", "Actions", "Releases", "Discussions", "Security", "Insights"),
            labels
        )
        assertFalse("Commits" in labels)
    }

    @Test
    fun `formatRepoDetailTabLabel appends open issue and PR counts in parentheses`() {
        assertEquals("Issues (14)", formatRepoDetailTabLabel(RepoDetailTab.ISSUES, openIssuesCount = 14, openPullRequestsCount = 3))
        assertEquals("PRs (3)", formatRepoDetailTabLabel(RepoDetailTab.PULLS, openIssuesCount = 14, openPullRequestsCount = 3))
        assertEquals("Issues", formatRepoDetailTabLabel(RepoDetailTab.ISSUES, openIssuesCount = null, openPullRequestsCount = 3))
        assertEquals("PRs", formatRepoDetailTabLabel(RepoDetailTab.PULLS, openIssuesCount = 14, openPullRequestsCount = null))
        assertEquals("Code", formatRepoDetailTabLabel(RepoDetailTab.CODE, openIssuesCount = 14, openPullRequestsCount = 3))
    }
}
