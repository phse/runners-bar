# Runners Bar

<img src="marketplace/plugin-icon.png" width="360" alt="Runners Bar icon for light and dark themes">

Plugin ID `app.heckit.ide.runnersbar` · Vendor heck\it ([heckit.app](https://heckit.app)) · [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34701-runners-bar) · Source: https://github.com/phse/runners-bar

![Runners Bar](marketplace/screenshots/01-runners-bar.png)

A plugin for JetBrains IDEs (PhpStorm, IntelliJ IDEA, … 2026.1 and later) that adds a slim bar
with your favourite run configurations right above the status bar.

- **+** (on the left) adds a run configuration or creates a new group (popup with search).
- **Groups**: as soon as there is more than one, a switcher appears next to the +. Use it to
  switch, rename and delete groups. A configuration can be part of several groups.
- **Drag and drop**: drag tabs to reorder them, just like editor tabs: the tab follows the mouse
  and the other tabs make room. Dragged outside the bar, the tab turns red with a trash icon;
  dropping it there removes it from the group. Press **Escape** while dragging to cancel.
- **Clicking a tab** starts the configuration right away (run, or debug if switched to debug mode).
- The **arrow** (or a right click) opens the menu:
  - Debug once / run once without the debugger
  - Debug mode (switch the tab to debug permanently)
  - Stop (while it is running)
  - Settings… (the configuration's editor)
  - Move left / move right
  - Remove
- Running configurations have a green background and a dot on their icon.
- If a configuration no longer exists, its tab stays with a warning icon until you remove it.
  Renamed configurations are followed.
- Show or hide the bar via **View | Appearance | Runners Bar** (all projects).
- Hide it in the current project only: **Hide in This Project** in the **+** menu, or
  **View | Appearance | Runners Bar in This Project**, which also brings it back.

Tabs, groups and the per-project visibility are stored per project in `.idea/workspace.xml`, so
they are personal and don't end up in version control. The UI is available in English and German.

## Installation

In the IDE, go to *Settings | Plugins | Marketplace* and search for **Runners Bar**, or use the
[Marketplace page](https://plugins.jetbrains.com/plugin/34701-runners-bar). Bugs and ideas are
welcome as [GitHub issues](https://github.com/phse/runners-bar/issues).

## Build and run

Gradle needs JDK 17 or later. The toolchain (JDK 25) is downloaded by Gradle via foojay if needed.

```
./gradlew runIde        # start a sandbox IDE with the plugin
./gradlew buildPlugin   # build/distributions/runners-bar-<version>.zip
```

Verify against the local IDE and the oldest supported version (`verifyOldestVersion`):

```
./gradlew verifyPlugin
```

Install a self-built ZIP: *Settings | Plugins | ⚙ | Install Plugin from Disk…*

By default Gradle downloads PhpStorm `platformVersion` (from `gradle.properties`) as the target
platform. With an installed IDE it's faster: create `local.properties` (not checked in) containing
e.g.

```
platformLocalPath=/Applications/PhpStorm.app
```

## Publishing (JetBrains Marketplace)

The Marketplace description lives in `src/main/resources/META-INF/plugin.xml`, the change notes in
`build.gradle.kts`, the icon in `META-INF/pluginIcon.svg` (light theme) and `pluginIcon_dark.svg`
(dark theme). The IDE and the Marketplace read the icon straight from the ZIP, it doesn't have to be
uploaded separately. After changing the icon, regenerate the preview `marketplace/plugin-icon.png`
at the top of this README (command below). Earlier drafts are in `marketplace/icon-proposals/`, the
current icon is variant `2a-pille-luftig`.

```
T=$(mktemp -d)
rsvg-convert -w 120 src/main/resources/META-INF/pluginIcon.svg -o $T/l.png
rsvg-convert -w 120 src/main/resources/META-INF/pluginIcon_dark.svg -o $T/d.png
magick -size 180x160 xc:'#F7F8FA' $T/l.png -gravity center -composite $T/L.png
magick -size 180x160 xc:'#2B2D30' $T/d.png -gravity center -composite $T/D.png
magick $T/L.png $T/D.png +append marketplace/plugin-icon.png
```

1. Bump the version in `gradle.properties`, add change notes.
2. `./gradlew clean buildPlugin verifyPlugin`
3. Create a token at https://plugins.jetbrains.com/author/me/tokens, then
   `PUBLISH_TOKEN=… ./gradlew publishPlugin`. Alternatively upload the ZIP from
   `build/distributions/` by hand on the
   [Marketplace page](https://plugins.jetbrains.com/plugin/34701-runners-bar). JetBrains reviews
   every version before it is released.
4. Tag the commit (`git tag v<version>`) and push.

Signing is optional (variables `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`,
see https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

## Regenerating the screenshots

```
./gradlew runIde -Pscreenshots=marketplace/screenshots
```

Copies `marketplace/demo-project` to `/Users/Shared/demo-shop` (so no user path shows up in the
images), opens it in the sandbox, starts `dev` and writes four PNGs to `marketplace/screenshots/`
(bar, tab menu, groups, add). The IDE paints its own window for this, no screen recording
permission is needed. The helper lives in `src/screenshot/` and is only compiled with
`-Pscreenshots`, never into a release.

Requirements: Node at `/usr/local/bin/node` (hard-coded in the demo run configurations) and
`/Users/Shared/demo-shop` as a trusted project in the sandbox. If the sandbox asks on the first
run, confirm "Trust Project" once and run the command again.

## How it works

There is no official extension point for the area above the status bar. The plugin therefore wraps
the status bar in its own container (bar on top, status bar below). If the IDE re-inserts the status
bar (e.g. in presentation mode), the bar attaches itself again automatically. When the plugin is
unloaded, the original state is restored.

| File | Contents |
| --- | --- |
| `RunnersBarInstaller.kt` | Startup activity, attaching above the status bar, visibility |
| `RunnersBarService.kt` | Project service, persistence, entries |
| `RunnersBarState.kt` | Persisted state (groups, entries, hidden per project) |
| `RunnersBarPanel.kt` | The bar, the "+" popup and drag and drop |
| `RunnersBarTab.kt` | Tab with click-to-run and menu |
| `RunnersBarExecution.kt` | Starting (run/debug) and detecting running processes |
| `ToggleRunnersBarAction.kt` | Toggles in *View \| Appearance* (global and per project) |
| `RunnersBarBundle.kt` | UI texts (English, German) in `resources/messages/` |

## License

Apache License 2.0, see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
Copyright 2026 Peter Heck (heck\it). The name and logo are not covered by the license.
