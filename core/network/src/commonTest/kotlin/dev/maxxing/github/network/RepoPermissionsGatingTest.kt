package dev.maxxing.github.network

import dev.maxxing.github.model.RepoDetail
import dev.maxxing.github.model.RepoSummary
import dev.maxxing.github.model.resolveRepoCapabilities
import dev.maxxing.github.model.resolveRepoWebUrl
import dev.maxxing.github.model.webUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoPermissionsGatingTest {

    private fun createClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
        }
    }

    private fun stubRepo(
        owner: String = "octocat",
        viewerPermission: String? = null,
        hasKnownPermission: Boolean = false,
        isArchived: Boolean = false,
        hasIssuesEnabled: Boolean = true
    ): RepoSummary = RepoSummary(
        id = "R_1",
        name = "app",
        owner = owner,
        fullName = "$owner/app",
        viewerPermission = viewerPermission,
        hasKnownPermission = hasKnownPermission,
        isArchived = isArchived,
        hasIssuesEnabled = hasIssuesEnabled
    )

    @Test
    fun `resolveRepoCapabilities enforces write triage read archived and owner fallback accurately`() {
        // 1. Collaborator / Owner roles grant push & triage
        for (perm in listOf("ADMIN", "MAINTAIN", "WRITE")) {
            val caps = resolveRepoCapabilities(stubRepo(viewerPermission = perm, hasKnownPermission = true))
            assertTrue(caps.canPush, "$perm must allow push actions")
            assertTrue(caps.canTriage, "$perm must allow triage actions")
            assertTrue(caps.canCreateIssue)
            assertTrue(caps.canCommentOrCreateDiscussion)
        }

        // 2. Triage role cannot push code/releases/actions, but can triage
        val triageCaps = resolveRepoCapabilities(stubRepo(viewerPermission = "TRIAGE", hasKnownPermission = true))
        assertFalse(triageCaps.canPush)
        assertTrue(triageCaps.canTriage)
        assertTrue(triageCaps.canCreateIssue)

        // 3. Explicit READ (including public third-party or read-only PAT) hides push & triage buttons
        val readCaps = resolveRepoCapabilities(stubRepo(owner = "octocat", viewerPermission = "READ", hasKnownPermission = true), viewerLogin = "octocat")
        assertFalse(readCaps.canPush)
        assertFalse(readCaps.canTriage)
        assertTrue(readCaps.canCreateIssue)

        val publicNullPermCaps = resolveRepoCapabilities(stubRepo(owner = "google", viewerPermission = null, hasKnownPermission = true), viewerLogin = "octocat")
        assertFalse(publicNullPermCaps.canPush)
        assertFalse(publicNullPermCaps.canTriage)

        // 4. Unknown permission before RepoDetail loads uses safe owner fallback, and upgrades once RepoDetail loads WRITE
        val unresolvedOwner = resolveRepoCapabilities(stubRepo(owner = "octocat"), detail = null, viewerLogin = "octocat")
        assertFalse(unresolvedOwner.permissionResolved)
        assertTrue(unresolvedOwner.canPush)

        val unresolvedOrgRepo = resolveRepoCapabilities(stubRepo(owner = "my-org"), detail = null, viewerLogin = "octocat")
        assertFalse(unresolvedOrgRepo.canPush)
        val resolvedOrgCollab = resolveRepoCapabilities(
            summary = stubRepo(owner = "my-org"),
            detail = RepoDetail(summary = stubRepo(owner = "my-org", viewerPermission = "WRITE", hasKnownPermission = true)),
            viewerLogin = "octocat"
        )
        assertTrue(resolvedOrgCollab.canPush)

        // 5. Archived repo hides all mutations even for ADMIN
        val archivedAdmin = resolveRepoCapabilities(stubRepo(viewerPermission = "ADMIN", hasKnownPermission = true, isArchived = true))
        assertFalse(archivedAdmin.canPush)
        assertFalse(archivedAdmin.canTriage)
        assertFalse(archivedAdmin.canCreateIssue)
        assertFalse(archivedAdmin.canCommentOrCreateDiscussion)

        // 6. Repo with issues disabled hides + Issue even for ADMIN while preserving push
        val noIssuesAdmin = resolveRepoCapabilities(stubRepo(viewerPermission = "ADMIN", hasKnownPermission = true, hasIssuesEnabled = false))
        assertTrue(noIssuesAdmin.canPush)
        assertFalse(noIssuesAdmin.canCreateIssue)
    }

    @Test
    fun `graphql and rest repository parsers populate permission archived and issues flags`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/graphql" -> respond(
                    content = """
                        {
                          "data": {
                            "repository": {
                              "id": "R_99",
                              "name": "lib",
                              "nameWithOwner": "org/lib",
                              "description": "Lib",
                              "isPrivate": false,
                              "isFork": false,
                              "isArchived": false,
                              "hasIssuesEnabled": false,
                              "viewerPermission": "READ",
                              "stargazerCount": 100,
                              "forkCount": 12,
                              "updatedAt": "2026-11-15T00:00:00Z",
                              "viewerHasStarred": false,
                              "owner": { "login": "org", "avatarUrl": "" },
                              "primaryLanguage": { "name": "Kotlin", "color": "#A97BFF" },
                              "defaultBranchRef": { "name": "main" },
                              "issues": { "totalCount": 7 },
                              "pullRequests": { "totalCount": 3 },
                              "homepageUrl": null,
                              "licenseInfo": null,
                              "watchers": { "totalCount": 5 },
                              "repositoryTopics": { "nodes": [] }
                            }
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                "/user/repos" -> respond(
                    content = """
                        [
                          {
                            "id": 7,
                            "node_id": "R_7",
                            "name": "mine",
                            "full_name": "octocat/mine",
                            "private": false,
                            "fork": false,
                            "archived": true,
                            "has_issues": true,
                            "permissions": { "admin": true, "maintain": true, "push": true, "triage": true, "pull": true },
                            "default_branch": "main",
                            "updated_at": "2026-11-15T00:00:00Z",
                            "owner": { "login": "octocat", "avatar_url": "" }
                          },
                          {
                            "id": 8,
                            "node_id": "R_8",
                            "name": "ro",
                            "full_name": "someone/ro",
                            "private": false,
                            "fork": false,
                            "default_branch": "main",
                            "updated_at": "2026-11-15T00:00:00Z",
                            "owner": { "login": "someone", "avatar_url": "" }
                          }
                        ]
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val client = createClient(engine)
        val gql = GitHubGraphQlService(client)
        val detail = gql.fetchRepoDetail("org", "lib")
        assertEquals("READ", detail.summary.viewerPermission)
        assertEquals(7, detail.summary.openIssuesCount)
        assertEquals(3, detail.summary.openPullRequestsCount)
        assertTrue(detail.summary.hasKnownPermission)
        assertFalse(detail.summary.isArchived)
        assertFalse(detail.summary.hasIssuesEnabled)

        val gqlCaps = resolveRepoCapabilities(detail.summary, detail, "octocat")
        assertFalse(gqlCaps.canPush)
        assertFalse(gqlCaps.canCreateIssue)

        val rest = GitHubRestService(client)
        val restRepos = rest.fetchViewerRepositoriesRest()
        assertEquals("ADMIN", restRepos[0].viewerPermission)
        assertTrue(restRepos[0].hasKnownPermission)
        assertTrue(restRepos[0].isArchived)
        assertNull(restRepos[1].viewerPermission)
        assertFalse(restRepos[1].hasKnownPermission)
    }

    @Test
    fun `resolveRepoWebUrl and RepoSummary webUrl build canonical github urls`() {
        assertEquals(
            "https://github.com/neutron42-jp/github-mobile-maxxing",
            resolveRepoWebUrl("neutron42-jp", "github-mobile-maxxing")
        )
        assertEquals(
            "https://github.com/octocat/hello-world",
            stubRepo(owner = "octocat").copy(name = "hello-world").webUrl()
        )
        assertEquals("https://github.com", resolveRepoWebUrl("", "app"))
    }
}
