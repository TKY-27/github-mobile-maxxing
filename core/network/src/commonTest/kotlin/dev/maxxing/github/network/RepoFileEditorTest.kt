package dev.maxxing.github.network

import dev.maxxing.github.auth.DeviceFlowClient
import dev.maxxing.github.auth.NotificationPreferences
import dev.maxxing.github.auth.SessionManager
import dev.maxxing.github.auth.ThemePreferences
import dev.maxxing.github.auth.TokenStore
import dev.maxxing.github.model.AuthSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FileEditorTestTokenStore : TokenStore {
    var session: AuthSession? = AuthSession("tok_editor", userLogin = "octocat")
    override suspend fun saveSession(session: AuthSession) { this.session = session }
    override suspend fun loadSession() = session
    override suspend fun clearSession() { session = null }
    override suspend fun saveClientId(clientId: String) {}
    override suspend fun loadClientId(): String? = null
    override suspend fun saveThemePreferences(prefs: ThemePreferences) {}
    override suspend fun loadThemePreferences() = ThemePreferences()
    override suspend fun saveNotificationPreferences(prefs: NotificationPreferences) {}
    override suspend fun loadNotificationPreferences() = NotificationPreferences()
    override suspend fun saveSeenNotificationMarkers(markers: Map<String, String>) {}
    override suspend fun loadSeenNotificationMarkers(): Map<String, String> = emptyMap()
}

class RepoFileEditorTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test
    fun fileMutationsEncodePathsLikeReads() = runTest {
        val paths = listOf(
            "docs/issue #42?.md" to "docs/issue%20%2342%3F.md",
            "docs/literal%2Fname.md" to "docs/literal%252Fname.md",
            "docs/日本語.md" to "docs/%E6%97%A5%E6%9C%AC%E8%AA%9E.md"
        )
        for ((path, encodedPath) in paths) {
            val methods = mutableListOf<HttpMethod>()
            val engine = MockEngine { request ->
                methods += request.method
                assertEquals("/repos/octo/app/contents/$encodedPath", request.url.encodedPath)
                assertEquals("", request.url.fragment)
                if (request.method == HttpMethod.Get) {
                    assertEquals(setOf("ref"), request.url.parameters.names())
                    assertEquals("feature/edit", request.url.parameters["ref"])
                    respond(
                        """{"name":"${path.substringAfterLast('/')}","path":"$path","sha":"blob-sha","type":"file","encoding":"base64","content":""}""",
                        headers = jsonHeaders
                    )
                } else {
                    assertTrue(request.url.parameters.isEmpty())
                    respond("""{"commit":{"sha":"commit-sha"}}""", headers = jsonHeaders)
                }
            }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(jsonCodec) }
            }
            try {
                val rest = GitHubRestService(client)
                val file = rest.fetchFileContent("octo", "app", path, ref = "feature/edit")
                rest.upsertRepoFile("octo", "app", path, "created", "", branch = "feature/edit")
                rest.upsertRepoFile("octo", "app", path, "updated", "", branch = "feature/edit", existingSha = file.sha)
                rest.deleteRepoFile("octo", "app", path, file.sha, "", branch = "feature/edit")
                assertEquals(listOf(HttpMethod.Get, HttpMethod.Put, HttpMethod.Put, HttpMethod.Delete), methods)
            } finally {
                client.close()
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun fileContentIncludesShaAndUpsertDeleteCommitAndInvalidateCaches() = runTest {
        val requests = mutableListOf<Triple<HttpMethod, String, String>>()
        val encodedInitial = Base64.Default.encode("hello world\n".encodeToByteArray())
        val engine = MockEngine { req ->
            val url = req.url.toString()
            val body = (req.body as? TextContent)?.text.orEmpty()
            requests += Triple(req.method, url, body)
            when {
                req.method == HttpMethod.Get && req.url.encodedPath == "/repos/octo/app/contents" -> respond(
                    content = """[{"name":"notes.md","path":"notes.md","sha":"sha_notes_1","size":12,"type":"file","html_url":"https://github.com/octo/app/blob/main/notes.md"}]""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders
                )
                req.method == HttpMethod.Get && req.url.encodedPath == "/repos/octo/app/contents/notes.md" -> respond(
                    content = """{"name":"notes.md","path":"notes.md","sha":"sha_notes_1","size":12,"type":"file","encoding":"base64","content":"$encodedInitial","html_url":"https://github.com/octo/app/blob/main/notes.md"}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders
                )
                req.method == HttpMethod.Put && req.url.encodedPath == "/repos/octo/app/contents/docs/todo.md" -> respond(
                    content = """{"commit":{"sha":"commit_create_123"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders
                )
                req.method == HttpMethod.Put && req.url.encodedPath == "/repos/octo/app/contents/notes.md" -> respond(
                    content = """{"commit":{"sha":"commit_update_456"}}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders
                )
                req.method == HttpMethod.Delete && req.url.encodedPath == "/repos/octo/app/contents/notes.md" -> respond(
                    content = """{"commit":{"sha":"commit_delete_789"}}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders
                )
                else -> error("Unexpected request ${req.method} $url")
            }
        }

        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(jsonCodec) }
        }
        val tokenStore = FileEditorTestTokenStore()
        val sessionManager = SessionManager(tokenStore, DeviceFlowClient(httpClient))
        sessionManager.initialize()
        val repo = GitHubRepository(sessionManager, httpClient = httpClient)

        // Load directory & file to warm caches
        val entries = repo.getDirectoryContents("octo", "app", "", "main").getOrThrow()
        assertEquals(1, entries.size)
        val fetched = repo.getFileContent("octo", "app", "notes.md", "main").getOrThrow()
        assertEquals("sha_notes_1", fetched.sha)
        assertEquals("hello world\n", fetched.content)
        assertFalse(repo.peekDirectoryContents("octo", "app", "", "main").isNullOrEmpty())
        assertEquals("sha_notes_1", repo.peekFileContent("octo", "app", "notes.md", "main")?.sha)

        // Create new file (no existing sha)
        val createSha = repo.upsertRepoFile(
            owner = "octo",
            repo = "app",
            path = "/docs/todo.md",
            content = "- ship editor\n",
            message = "Add todo",
            branch = "main",
            existingSha = null
        ).getOrThrow()
        assertEquals("commit_create_123", createSha)
        val createReq = requests.first { it.first == HttpMethod.Put && it.second.contains("docs/todo.md") }
        val createJson = jsonCodec.parseToJsonElement(createReq.third).jsonObject
        assertEquals("Add todo", createJson["message"]?.jsonPrimitive?.content)
        assertEquals("main", createJson["branch"]?.jsonPrimitive?.content)
        assertNull(createJson["sha"])
        assertEquals("- ship editor\n", Base64.Default.decode(createJson["content"]!!.jsonPrimitive.content).decodeToString())
        // Caches invalidated after create
        assertNull(repo.peekDirectoryContents("octo", "app", "", "main"))

        // Edit existing file (requires sha)
        val editSha = repo.upsertRepoFile(
            owner = "octo",
            repo = "app",
            path = "notes.md",
            content = "updated notes\n",
            message = "Update notes",
            branch = "feature/edit",
            existingSha = fetched.sha
        ).getOrThrow()
        assertEquals("commit_update_456", editSha)
        val editReq = requests.first { it.first == HttpMethod.Put && it.second.contains("notes.md") }
        val editJson = jsonCodec.parseToJsonElement(editReq.third).jsonObject
        assertEquals("sha_notes_1", editJson["sha"]?.jsonPrimitive?.content)
        assertEquals("feature/edit", editJson["branch"]?.jsonPrimitive?.content)
        assertEquals("updated notes\n", Base64.Default.decode(editJson["content"]!!.jsonPrimitive.content).decodeToString())

        // Delete existing file
        val delSha = repo.deleteRepoFile(
            owner = "octo",
            repo = "app",
            path = "notes.md",
            sha = fetched.sha,
            message = "Remove notes",
            branch = "main"
        ).getOrThrow()
        assertEquals("commit_delete_789", delSha)
        val delReq = requests.first { it.first == HttpMethod.Delete && it.second.contains("notes.md") }
        val delJson = jsonCodec.parseToJsonElement(delReq.third).jsonObject
        assertEquals("sha_notes_1", delJson["sha"]?.jsonPrimitive?.content)
        assertEquals("Remove notes", delJson["message"]?.jsonPrimitive?.content)
        assertTrue(requests.any { it.first == HttpMethod.Delete })
    }
}
