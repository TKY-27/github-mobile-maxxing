package dev.maxxing.github.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.SlidersHorizontal
import dev.maxxing.github.app.AppContainer
import dev.maxxing.github.app.MaxxingBuildConfig
import dev.maxxing.github.app.notifications.PlatformNotifier
import dev.maxxing.github.auth.inspectGitHubLinkHandling
import dev.maxxing.github.auth.openOfficialGitHubAppLinkSettings
import dev.maxxing.github.auth.openThisAppLinkSettings
import dev.maxxing.github.ui.components.MaxxingColorPickerDialog
import dev.maxxing.github.ui.components.MaxxingDropdownSelector
import dev.maxxing.github.ui.components.MaxxingWavyProgress
import dev.maxxing.github.ui.components.UserAvatar
import dev.maxxing.github.ui.components.formatSeedColorHex
import dev.maxxing.github.ui.theme.MaxxingSeedPresets
import kotlinx.coroutines.launch

private enum class DarkModeOption(val label: String, val value: Boolean?) {
    SYSTEM("System", null),
    LIGHT("Light", false),
    DARK("Dark", true)
}

private enum class PollIntervalOption(val label: String, val minutes: Int) {
    ONE("1 min", 1),
    FIVE("5 min", 5),
    FIFTEEN("15 min", 15)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenUser: (String) -> Unit = {},
    onSignedOut: () -> Unit,
    onMessage: (String) -> Unit
) {
    val session by container.sessionManager.session.collectAsState()
    val profile by container.viewerProfile.collectAsState()
    val rateLimit by container.repository.rateLimit.collectAsState()
    val themePrefs by container.sessionManager.themePreferences.collectAsState()
    val notifPrefs by container.sessionManager.notificationPreferences.collectAsState()
    val savedClientId by container.sessionManager.clientId.collectAsState()

    var clientIdField by remember(savedClientId) { mutableStateOf(savedClientId) }
    var showCustomColorPicker by remember { mutableStateOf(false) }
    var linkHandlingState by remember { mutableStateOf(inspectGitHubLinkHandling()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                linkHandlingState = inspectGitHubLinkHandling()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings & Appearance",
                style = MaterialTheme.typography.headlineMedium
            )

            // Account card
            ElevatedCard(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .clickable(enabled = (profile?.login ?: session?.userLogin)?.isNotBlank() == true) {
                            val loginTarget = profile?.login ?: session?.userLogin
                            if (!loginTarget.isNullOrBlank()) onOpenUser(loginTarget)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(
                        avatarUrl = profile?.avatarUrl.orEmpty(),
                        login = profile?.login ?: session?.userLogin ?: "?",
                        sizeDp = 56
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.name ?: "@${profile?.login ?: session?.userLogin ?: "GitHub User"}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "@${profile?.login ?: session?.userLogin ?: "unknown"} • ${session?.authMethod?.name ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!profile?.bio.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = profile?.bio.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                container.sessionManager.signOut()
                                container.clearCachedProfile()
                                onSignedOut()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Lucide.LogOut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign Out")
                    }
                }
            }

            // Rate Limit card
            ElevatedCard(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("GitHub API Rate Limit", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${rateLimit.remaining} / ${rateLimit.limit}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    val ratio = if (rateLimit.limit > 0) {
                        rateLimit.remaining.toFloat() / rateLimit.limit.toFloat()
                    } else 1f
                    MaxxingWavyProgress(progress = ratio)
                }
            }

            // Notifications card
            ElevatedCard(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Bell, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("GitHub OS Notifications", style = MaterialTheme.typography.titleMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable OS Alerts", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Android notifications with Mark Read / Done actions & Linux notify-send.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notifPrefs.osNotificationsEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    container.sessionManager.updateNotificationPreferences(
                                        notifPrefs.copy(osNotificationsEnabled = enabled)
                                    )
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Urgent Only", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Only alert for Review Requests, Mentions, Assignments, and Security.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notifPrefs.urgentOnly,
                            enabled = notifPrefs.osNotificationsEnabled,
                            onCheckedChange = { urgent ->
                                scope.launch {
                                    container.sessionManager.updateNotificationPreferences(
                                        notifPrefs.copy(urgentOnly = urgent)
                                    )
                                }
                            }
                        )
                    }

                    val currentPoll = PollIntervalOption.entries.firstOrNull {
                        it.minutes == notifPrefs.pollIntervalMinutes
                    } ?: PollIntervalOption.FIVE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Foreground Sync Interval",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        MaxxingDropdownSelector(
                            options = PollIntervalOption.entries,
                            selected = currentPoll,
                            onSelect = { opt ->
                                scope.launch {
                                    container.sessionManager.updateNotificationPreferences(
                                        notifPrefs.copy(pollIntervalMinutes = opt.minutes)
                                    )
                                }
                            },
                            labelProvider = { it.label }
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            val sent = PlatformNotifier.showTestNotification()
                            onMessage(
                                if (sent) "Test notification dispatched"
                                else "Notifications blocked or notify-send unavailable — check OS permissions"
                            )
                        }
                    ) {
                        Icon(Lucide.Bell, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send Test Notification")
                    }
                }
            }

            // MD3E Theme customizer
            ElevatedCard(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Material 3 Expressive Theme", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))

                    val currentMode = DarkModeOption.entries.first { it.value == themePrefs.darkModeOverride }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Appearance",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        MaxxingDropdownSelector(
                            options = DarkModeOption.entries,
                            selected = currentMode,
                            onSelect = { opt ->
                                scope.launch {
                                    container.sessionManager.updateThemePreferences(
                                        themePrefs.copy(darkModeOverride = opt.value)
                                    )
                                }
                            },
                            labelProvider = { it.label }
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    val activePreset = MaxxingSeedPresets.firstOrNull { it.colorHex == themePrefs.seedColorHex }
                    val isCustomColorSelected = activePreset == null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Expressive Seed Accent (SPEC_2025)", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "${activePreset?.name ?: "Custom"} · ${formatSeedColorHex(themePrefs.seedColorHex)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showCustomColorPicker = true }) {
                            Icon(Lucide.SlidersHorizontal, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Custom")
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MaxxingSeedPresets.forEach { preset ->
                            val selected = themePrefs.seedColorHex == preset.colorHex
                            val swatchColor = Color(preset.colorHex)
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .then(
                                        if (selected) {
                                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                        }
                                    )
                                    .clickable {
                                        scope.launch {
                                            container.sessionManager.updateThemePreferences(
                                                themePrefs.copy(
                                                    seedColorHex = preset.colorHex,
                                                    useDynamicColor = false
                                                )
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        Lucide.Check,
                                        contentDescription = preset.name,
                                        tint = if (swatchColor.luminance() > 0.45f) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        val customBg = if (isCustomColorSelected) {
                            Color(themePrefs.seedColorHex)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(customBg)
                                .then(
                                    if (isCustomColorSelected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    }
                                )
                                .clickable { showCustomColorPicker = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Lucide.SlidersHorizontal,
                                contentDescription = "Pick custom color",
                                tint = if (isCustomColorSelected) {
                                    if (customBg.luminance() > 0.45f) Color.Black else Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Android Wallpaper Dynamic Color", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Uses Material You system palette on Android 12+, falls back to seed on Linux.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = themePrefs.useDynamicColor,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    container.sessionManager.updateThemePreferences(
                                        themePrefs.copy(useDynamicColor = enabled)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            if (linkHandlingState.supportedOnPlatform) {
                ElevatedCard(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.Link2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("GitHub Link Handling", style = MaterialTheme.typography.titleMedium)
                        }
                        val statusSummary = when {
                            linkHandlingState.thisAppHandlesGitHubLinks ->
                                "github.com links are set to open in github-mobile-maxxing."
                            linkHandlingState.officialGitHubInstalled &&
                                linkHandlingState.officialGitHubHandlesLinks == true ->
                                "Official GitHub Mobile currently claims github.com links. Disable \"Open supported links\" there first, then enable github.com here."
                            linkHandlingState.officialGitHubInstalled ->
                                "Official GitHub Mobile is installed. Unlink it if it intercepts github.com links, then enable github.com for this app."
                            else ->
                                "Enable github.com under Open by default so web links launch directly in this app."
                        }
                        Text(
                            text = statusSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (linkHandlingState.officialGitHubInstalled) {
                                OutlinedButton(
                                    onClick = {
                                        if (!openOfficialGitHubAppLinkSettings()) {
                                            onMessage("Could not open GitHub Mobile app link settings")
                                        }
                                    }
                                ) {
                                    Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Unlink GitHub Mobile")
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (!openThisAppLinkSettings()) {
                                        onMessage("Could not open app link settings")
                                    }
                                }
                            ) {
                                Icon(Lucide.Link2, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (linkHandlingState.thisAppHandlesGitHubLinks) {
                                        "Manage App Links"
                                    } else {
                                        "Enable github.com Links"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // OAuth Client ID config
            ElevatedCard(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("OAuth Client Configuration", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = clientIdField,
                            onValueChange = { clientIdField = it },
                            label = { Text("GitHub OAuth Client ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    container.sessionManager.updateClientId(clientIdField)
                                    onMessage("Saved Client ID")
                                }
                            }
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            SelectionContainer {
                Text(
                    text = formatAppBuildFooter(
                        versionName = MaxxingBuildConfig.APP_VERSION_NAME,
                        commitSha = MaxxingBuildConfig.APP_COMMIT_SHA
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp)
                )
            }
        }
    }

    if (showCustomColorPicker) {
        MaxxingColorPickerDialog(
            initialColorHex = themePrefs.seedColorHex,
            onDismiss = { showCustomColorPicker = false },
            onSelectColor = { chosenHex ->
                showCustomColorPicker = false
                scope.launch {
                    container.sessionManager.updateThemePreferences(
                        themePrefs.copy(
                            seedColorHex = chosenHex,
                            useDynamicColor = false
                        )
                    )
                }
            }
        )
    }
}

internal fun formatAppBuildFooter(versionName: String, commitSha: String): String {
    val cleanVersion = versionName.trim().removePrefix("v").ifBlank { "dev" }
    val cleanCommit = commitSha.trim().ifBlank { "unknown" }
    return "v$cleanVersion ($cleanCommit)"
}
