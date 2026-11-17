# github-mobile-maxxing

A fast, custom **GitHub client** built with **Kotlin Multiplatform (KMP)** and **Compose Multiplatform**, targeting **Android** and **Linux Desktop**, powered by **Material 3 Expressive (MD3E)**.

## Features
- **GitHub OAuth Device Flow**: Zero-secret GitHub OAuth Device Flow (`https://github.com/login/device/code`) with a bundled default Client ID (`Ov23lihCAIZTj1Puaf9r`), external browser launch, one-click code copy, and adaptive polling (`slow_down` / `authorization_pending`). Custom OAuth Client IDs can also be configured optionally on the Sign-In or Settings screen.
- **Platform-Native Secure Token Storage**:
  - **Android**: Hardware-backed Android Keystore AES-256-GCM encrypted session storage.
  - **Linux**: Freedesktop Secret Service (`secret-tool` / GNOME Keyring / KDE KWallet) with owner-only (`0600`) XDG Data fallback.
- **Inbox & OS Notifications**:
  - Swipe-to-done / mark-read triage, Unread/Participating/All filter, and adaptive split-pane Issue/PR preview.
  - Background notification polling (Android WorkManager / Desktop notifications) with notification deep links (`githubmaxxing://thread/...`).
- **Issues & Pull Requests**:
  - Single-query GraphQL timeline (Markdown comments & editing, reactions, reviews, commits, labels, assignees, milestones).
  - Inline unified Diff viewer and tap-to-open **full-screen Diff viewer** (word-wrap toggle, font size controls, prev/next file switching).
  - PR Review submission (`Approve` / `Request Changes` / `Comment`), Merge `SplitButton` (`Squash` / `Merge Commit` / `Rebase`), full-screen Markdown editor with live preview, and Issue creation with label selection/creation.
- **Repositories, Profiles & Gists**:
  - My Repos, Starred, Global Search (Repos / Issues & PRs / Code / Users), Branch & tag switcher, File Tree + syntax-highlighted Code Viewer, inline file create/edit/delete, and README Markdown rendering.
  - Star/Watch/Fork/Network/Share (`Copy URL`, system share, browser), open Issue/PR tab counts (`Issues (N)` / `PRs (M)`), Commits & Branch Compare, Actions & Job Logs, Releases, Discussions, Security alerts, Insights, User Profiles, and Gists.
- **Android App Links (`github.com`)**:
  - Opens `https://github.com/...` repository, issue, PR, and user links directly in-app, with Settings shortcuts to unlink official GitHub Mobile and enable `github.com` link handling for `github-mobile-maxxing`.
- **Material 3 Expressive (MD3E) & Typography**:
  - `MaterialExpressiveTheme` + `MotionScheme.expressive()` + MaterialKolor `SPEC_2025` dynamic palettes + Android 12+ Wallpaper Dynamic Color.
  - Curated seed color presets, interactive **Custom Accent Color** picker (HSV pad, Hue bar, `#RRGGBB` input), and customizable **Primary + per-glyph Fallback** fonts (system fonts, generic families, and `.ttf`/`.otf`/`.ttc` import).

## Downloads
- **Stable Releases**: Signed Android APK (`github-mobile-maxxing-release.apk`) and Linux `x86_64` AppImage (`github-mobile-maxxing-x86_64.AppImage`) on the [Releases](https://github.com/neutron42-jp/github-mobile-maxxing/releases) page.
- **Nightly Builds**: Automated `nightly` prereleases built from `main` (Obtainium-compatible).

## Setup & Build

### 1. GitHub OAuth Client ID (Optional)
A default Device Flow Client ID (`Ov23lihCAIZTj1Puaf9r`) is built in out of the box. To use your own OAuth/GitHub App instead:
1. Enable **Device Flow** in your GitHub OAuth/GitHub App settings.
2. Add `github.client.id=YOUR_CLIENT_ID` to `local.properties` to bake it in at build time, **or** enter a custom Client ID on the Sign-In or Settings screen.

### 2. Build & Run

#### Linux Desktop
```bash
./gradlew :composeApp:run
```
Create a standalone Linux `AppImage` (`composeApp/build/compose/binaries/main/appimage/github-mobile-maxxing-x86_64.AppImage`):
```bash
./gradlew :composeApp:packageLinuxAppImage
```

#### Android
Build the R8-optimized release APK (uses release keystore if configured via `ANDROID_KEYSTORE_*` / `local.properties`, otherwise falls back to the debug keystore for local `adb install`):
```bash
./gradlew :androidApp:assembleRelease
```
Output APK: `androidApp/build/outputs/apk/release/androidApp-release.apk`.

Debug build (unminified, useful for profiling/debugging):
```bash
./gradlew :androidApp:assembleDebug
```
Output APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

#### Tests
```bash
./gradlew \
  :core:model:desktopTest \
  :core:auth:desktopTest \
  :core:ui:desktopTest \
  :core:network:desktopTest \
  :composeApp:desktopTest \
  :composeApp:testAndroidHostTest
```
