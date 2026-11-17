package dev.maxxing.github.app

import io.ktor.http.Url

sealed interface AppDeepLink {
    data class IssueOrPull(val target: IssuePullTarget, val fromNotification: Boolean = false) : AppDeepLink
    data class Repository(val owner: String, val repo: String) : AppDeepLink
    data class User(val login: String) : AppDeepLink
    data class ExternalWeb(val url: String) : AppDeepLink
}

private val GITHUB_RESERVED_ROOT_PATHS = setOf(
    "about",
    "apps",
    "codespaces",
    "collections",
    "contact",
    "copilot",
    "dashboard",
    "enterprise",
    "events",
    "explore",
    "features",
    "gist",
    "gists",
    "issues",
    "join",
    "login",
    "logout",
    "marketplace",
    "new",
    "notifications",
    "orgs",
    "organizations",
    "pricing",
    "pulls",
    "readme",
    "search",
    "security",
    "settings",
    "signup",
    "site",
    "solutions",
    "sponsors",
    "stars",
    "topics",
    "trending",
    "users",
    "watching"
)

/**
 * Parses both internal `githubmaxxing://thread/...` notification links and `https://github.com/...` web links
 * into strongly typed in-app destinations.
 */
fun parseAppDeepLink(rawUrl: String): AppDeepLink? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null

    val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
    val scheme = parsed.protocol.name.lowercase()
    val host = parsed.host.lowercase()
    val segments = parsed.rawSegments.filter { it.isNotBlank() }

    if (scheme == "githubmaxxing" && host == "thread") {
        if (segments.size < 3) return null
        val owner = segments[0]
        val repo = segments[1]
        val number = segments[2].toIntOrNull() ?: return null
        return AppDeepLink.IssueOrPull(
            target = IssuePullTarget(owner = owner, repo = repo, number = number),
            fromNotification = true
        )
    }

    if ((scheme == "https" || scheme == "http") && (host == "github.com" || host == "www.github.com")) {
        if (segments.isEmpty()) return AppDeepLink.ExternalWeb(trimmed)
        val first = segments[0]
        if (first.lowercase() in GITHUB_RESERVED_ROOT_PATHS) {
            return AppDeepLink.ExternalWeb(trimmed)
        }
        if (segments.size == 1) {
            return AppDeepLink.User(login = first)
        }
        val owner = first
        val repo = segments[1].removeSuffix(".git")
        if (repo.isBlank()) return AppDeepLink.ExternalWeb(trimmed)

        if (segments.size >= 4 && (segments[2] == "issues" || segments[2] == "pull")) {
            val number = segments[3].toIntOrNull()
            if (number != null && number > 0) {
                return AppDeepLink.IssueOrPull(
                    target = IssuePullTarget(
                        owner = owner,
                        repo = repo,
                        number = number,
                        hintTitle = "#$number"
                    ),
                    fromNotification = false
                )
            }
        }
        return AppDeepLink.Repository(owner = owner, repo = repo)
    }

    return null
}
