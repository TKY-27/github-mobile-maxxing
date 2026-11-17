package dev.maxxing.github.app

import androidx.compose.runtime.saveable.Saver
import dev.maxxing.github.model.RepoSummary

enum class ShellDestination {
    HOME,
    INBOX,
    ISSUES_PRS,
    REPOS,
    SETTINGS
}

data class ShellNavState(
    val currentDestination: ShellDestination = ShellDestination.HOME,
    val profileBackStack: List<String> = emptyList(),
    val repoBackStack: List<RepoSummary> = emptyList(),
    val overlayRepo: RepoSummary? = null,
    val overlayIssue: IssuePullTarget? = null,
    val overlayGistsUser: String? = null,
    val tabReselectNonce: Int = 0
) {
    val activeRepo: RepoSummary?
        get() = repoBackStack.lastOrNull() ?: overlayRepo

    val hasOverlay: Boolean
        get() = overlayGistsUser != null ||
            overlayIssue != null ||
            activeRepo != null ||
            profileBackStack.isNotEmpty()

    fun selectBottomDestination(destination: ShellDestination): ShellNavState {
        val cleared = copy(
            currentDestination = destination,
            profileBackStack = emptyList(),
            repoBackStack = emptyList(),
            overlayRepo = null,
            overlayIssue = null,
            overlayGistsUser = null,
            tabReselectNonce = if (currentDestination == destination || hasOverlay) {
                tabReselectNonce + 1
            } else {
                tabReselectNonce
            }
        )
        return cleared
    }

    fun openUserProfile(login: String): ShellNavState {
        if (login.isBlank()) return this
        return copy(
            repoBackStack = emptyList(),
            overlayRepo = null,
            overlayIssue = null,
            overlayGistsUser = null,
            profileBackStack = profileBackStack + login
        )
    }

    fun openRepo(repo: RepoSummary): ShellNavState {
        val base = if (repoBackStack.isNotEmpty()) repoBackStack else listOfNotNull(overlayRepo)
        val next = if (base.lastOrNull()?.fullName?.equals(repo.fullName, ignoreCase = true) == true) {
            base.dropLast(1) + repo
        } else {
            base + repo
        }
        return copy(
            repoBackStack = next,
            overlayRepo = next.lastOrNull()
        )
    }

    fun popRepo(): ShellNavState {
        val base = if (repoBackStack.isNotEmpty()) repoBackStack else listOfNotNull(overlayRepo)
        val next = if (base.isNotEmpty()) base.dropLast(1) else emptyList()
        return copy(
            repoBackStack = next,
            overlayRepo = next.lastOrNull()
        )
    }

    fun popBackOrReturnHome(): ShellNavState = when {
        overlayGistsUser != null -> copy(overlayGistsUser = null)
        overlayIssue != null -> copy(overlayIssue = null)
        activeRepo != null -> popRepo()
        profileBackStack.isNotEmpty() -> copy(profileBackStack = profileBackStack.dropLast(1))
        currentDestination != ShellDestination.HOME -> copy(currentDestination = ShellDestination.HOME)
        else -> this
    }
}

/**
 * Saves only the bottom destination, so the app comes back on the same tab after Android recreates
 * the process (for example while the system file picker is open from Settings, whose result is then
 * still delivered). Back stacks and overlays start empty. An unknown name restores HOME.
 */
internal val ShellNavStateSaver: Saver<ShellNavState, String> = Saver(
    save = { it.currentDestination.name },
    restore = { name ->
        ShellNavState(
            currentDestination = ShellDestination.entries.firstOrNull { it.name == name } ?: ShellDestination.HOME
        )
    }
)
