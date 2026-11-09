# Runners Bar

<img src="marketplace/plugin-icon.png" width="360" alt="Runners Bar icon for light and dark themes">

Plugin ID `app.heckit.ide.runnersbar` · Vendor heck\it ([heckit.app](https://heckit.app)) · [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34701-runners-bar) · Source: https://github.com/phse/runners-bar · Support: [GitHub Sponsors](https://github.com/sponsors/phse) or [Ko-fi](https://ko-fi.com/phse_de) · Feedback and feature requests: [plugin-runners-bar@heckit.app](mailto:plugin-runners-bar@heckit.app)

![Runners Bar](marketplace/screenshots/01-runners-bar.png)

A plugin for JetBrains IDEs (PhpStorm, IntelliJ IDEA, … 2026.1 and later) that adds a slim bar
with your favourite run configurations above the status bar or below the toolbar.

- The **bar menu** (menu button: play arrow in a white pill, see the settings for plainer ones) adds a run configuration (popup with search). **New Run Configuration** creates a new
  one: pick the type, fill in the editor, and after *OK* it is added to the bar right away.
  Right-clicking an empty spot of the bar opens the bar menu's **Runners Bar** submenu at the mouse.
- **Run Anything** (double Ctrl): `bar npm run dev` (any command after the prefix) asks for a name,
  creates a configuration for the command in the selected directory, adds it to the bar and runs
  it. If another Run Anything provider understands the command (npm scripts, Gradle, Maven, …), its
  own configuration type is used; otherwise a Shell Script configuration that runs in the Run tool
  window. The prefix `bar` can be changed in the settings (one value for all projects). Needs the
  bundled Shell Script plugin.
- **From anywhere**: right-click a place the IDE can run (a script in `package.json`, a test, a
  main method, …) or open the menu of the green run icon in the gutter and choose **Add to Runners
  Bar...**. It asks for a name and a group (the current one, any other or a new name), creates the configuration if
  needed and adds it there.
- **VS Code tasks**: if the project (or one of its direct subfolders) has a `.vscode/tasks.json`,
  a notification offers to import the tasks (*Import...*, *Not now*, *Don't offer again*). The
  import dialog lists every task with its command; pick the ones you want. Each folder becomes a
  group, each task a Shell Script configuration that runs in the terminal, in its folder, with its
  `env`. Supported: `shell`, `process`, `npm`, `gulp`, `grunt`, `jake`, `docker-build` and
  `docker-compose` tasks, comments in the file, `${workspaceFolder}`, `${env:…}` and the `osx`/
  `linux`/`windows` variants. Tasks that need VS Code (`${file}`, `${input:…}`, only `dependsOn`,
  extension types such as `docker-run`) are shown greyed out with the reason. Imported
  configurations are personal. Run the import again (bar menu | Runners Bar | *Import VS Code Tasks...*
  or Find Action) to add the tasks you left out; tasks already imported are updated from the file.
  The notification can be switched off in the settings; it only comes back for new tasks.
- **DDEV**: in a DDEV project (`.ddev/config.yaml` in the project or a direct subfolder) a
  notification offers to import the usual commands (`ddev start`, `stop`, `restart`, `describe`,
  `launch`, `ssh`, `logs -f`, `composer install`, `import-db`, `snapshot`) and the project's own
  commands from `.ddev/commands/*/` (with their `## Description:`) into a group *DDEV*. Commands
  DDEV ships itself (`#ddev-generated`) are listed but not preselected. They run in the terminal,
  in the DDEV project folder. Single commands can also be added via bar menu | *New Run Configuration* |
  *DDEV*; the import is in bar menu | Runners Bar | *Import DDEV Commands...*.
- **Project scripts**: bar menu | Runners Bar | *Import Project Scripts...* (or Find Action) lists the scripts of
  `package.json` (run with npm, pnpm, yarn or bun, after the lockfile) and `composer.json`, the targets of a `Makefile`
  (descriptions from `## …`) and the recipes of a `justfile` (description from the comment above), in the project and its
  direct subfolders. Lifecycle scripts (`pre…`/`post…`, `postinstall`, Composer events) are listed but not preselected;
  justfile recipes that need arguments are greyed out. Right-click one of these files (project view, editor or editor
  tab) and choose *Import Scripts to Runners Bar...* to import just that file, at any depth. A notification on project
  open can be switched on in the settings (off by default, as nearly every project has a `package.json`).
- **Target group**: every import dialog (VS Code, DDEV, project scripts) suggests a group per section, like a
  changelist: keep it, pick an existing group or type a new name.
- Imported tasks (VS Code, DDEV and project scripts) run in the terminal only if they need one (ssh, database
  clients, `read`, `-it`, `ddev import-db`, …); the rest run in the Run window, where *Restart*
  and *Stop* work. The import dialog presets a *Terminal* checkbox per task accordingly. Existing
  tabs: Shell | *Execute in the Terminal* in their menu.
- Configurations that Run Anything (or the IDE) created **temporarily** appear in the bar menu
  in the *Temporary Configurations* submenu, together with shell commands recently run through
  Run Anything (those become Shell Script configurations). Adding one asks for a name and keeps it
  for good.
- **New Group…** in the bar menu creates a group. The **Runners Bar** submenu hides the bar in the current
  project, and switches position (top/bottom), tab alignment (left/center/right) and the side of
  the controls (left/right).
- **Groups**: as soon as there is more than one, a switcher appears next to the bar menu. Use it to
  switch, rename and delete groups. *Sort Tabs Alphabetically* tidies up a group after an import,
  *Remove Tabs Without Configuration…* drops tabs whose run configuration was deleted (both also
  in the bar menu's **Runners Bar** submenu). A configuration can be part of several groups. The last
  active group is remembered per project.
- **Move or copy to another group**: *Move to Group* in the tab menu lists the other groups
  (greyed out where the configuration already is) and *New Group…*. Hold **⌥** (macOS) or
  **Ctrl** (Windows, Linux) to copy instead; the entry then reads *Copy to Group*. A
  notification confirms it, with *Undo* and *Switch to Group*.
- **Team groups**: *Share with the Team…* (group switcher or the bar menu's **Runners Bar** submenu,
  or a checkbox in the settings) moves a group to `.idea/runnersBar.xml`. Commit that file and
  everyone gets the same tabs, e.g. *Start dev*, *Tests*, *Deploy staging*. Run configurations of a
  shared group that live only in your workspace are stored as project files
  (`.idea/runConfigurations`) so they exist for everyone; the confirmation names them. Shared groups
  appear in their own section of the group switcher and are reloaded when the file changes (e.g.
  after `git pull`). *Stop Sharing…* moves a group back to your workspace.
- **Project file `.runners-bar`**: a small JSON file (comments allowed) in the project root, like
  `.editorconfig`. Its runners become shell run configurations in their groups when the project
  opens and whenever the file changes. The file is the source: command, folder (`cwd`, relative to
  the project), environment and `terminal` follow it; runners removed from the file are deleted.
  Without `terminal`, it is detected from the command as for VS Code tasks. Handy for project
  templates or an AI agent setting up a project. A run icon in the gutter next to each runner saves the file and runs
  it:

  ```jsonc
  {
    "groups": [
      {
        "name": "Plugin",
        "runners": [
          { "name": "Sandbox", "command": "./gradlew runIde", "terminal": false },
          { "name": "Publish", "command": "./gradlew publishPlugin", "terminal": false }
        ]
      }
    ]
  }
  ```
- **Drag and drop**: drag tabs to reorder them, just like editor tabs: the tab follows the mouse
  and the other tabs make room. Dragged outside the bar, the tab turns red with a trash icon;
  dropping it there removes it from the group. Keep it outside for half a second and a list of
  the other groups appears next to the mouse: drop it on a group to move it there, with **⌥** /
  **Ctrl** to copy it (a green + shows on the tab). Press **Escape** while dragging to cancel.
- **Clicking a tab** starts the configuration right away (run, or debug if switched to debug mode).
- The **arrow** (or a right click) opens the menu:
  - Debug once / run once without the debugger
  - Debug mode (switch the tab to debug permanently)
  - Restart and Stop (while it is running)
  - Settings… (the configuration's editor)
  - Rename…
  - Duplicate… (opens the copy's editor; after *OK* it is placed right behind the original)
  - Move to Group / Copy to Group (see above)
  - Shell (Shell Script configurations only): *Execute in the Terminal* and, if a Run Anything
    provider understands the command, convert it to that provider's configuration (runs it)
  - Remove (from the bar) / Delete… (deletes the configuration, after a confirmation)
- Running configurations have a green background and a dot on their icon.
- If a configuration no longer exists, its tab stays with a warning icon until you remove it.
  Renamed configurations are followed.
- Show or hide the bar via **View | Appearance | Runners Bar** (default for all projects).
- Show or hide it in the current project only: **Hide in This Project** in the bar menu's
  **Runners Bar** submenu, or **View | Appearance | Runners Bar in This Project**. The project
  setting wins over the default, so a project can also show the bar while it is off by default.
- **Settings | Appearance & Behavior | Runners Bar**: a table with one row per setting: show or
  hide the bar, position *top* (below the toolbar) or *bottom* (above the status bar), tabs *left*,
  *center* or *right*, controls (bar menu, group switcher) *left* or *right*, menu arrow on the tabs shown
  or hidden (the menu is always available with a right click), and the menu button: a play arrow
  in a white pill (default), a filled or outlined play arrow, or a plus (all projects only). The first column holds the
  **defaults for all projects**, the second one the current project's value or *Same as all
  projects*. Below the table, every group of the project has a *Share with the team* checkbox,
  and *VS Code tasks* switches the import notification on or off.
  The **Runners Bar** submenu of the bar menu changes the current project only; choosing the default
  value there makes the project follow the default again. New tabs are always appended on the right.

Tabs, groups and the per-project visibility are stored per project in `.idea/workspace.xml`, so
they are personal and don't end up in version control, together with the project's own layout
values. Shared groups are the exception: they live in `.idea/runnersBar.xml`. The defaults are IDE
settings (`options/runnersBar.xml`). The UI is available in English and German.

![Settings](marketplace/screenshots/06-settings.png)

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
at the top of this README (command below). The current icon fills the whole square like all
heck\it plugins: gradient `#B04079` → `#5A5A8C` → `#2A7AA0` (45°, same in light and dark theme)
with a white pill, the controls inside in the same gradient.

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
   `PUBLISH_TOKEN=… ./gradlew publishPlugin`. The token belongs to your JetBrains account, not to a
   vendor, so it can be kept globally in `~/.gradle/gradle.properties` as
   `jetbrainsMarketplaceToken=…`; then `./gradlew publishPlugin` is enough. Alternatively upload the ZIP from
   `build/distributions/` by hand on the
   [Marketplace page](https://plugins.jetbrains.com/plugin/34701-runners-bar). JetBrains reviews
   every version before it is released.
4. Tag the commit (`git tag v<version>`) and push.
5. If `marketplace/custom-pages/` changed, paste the page into the Marketplace by hand (plugin page,
   *Custom Pages* tab, Markdown). `publishPlugin` doesn't upload custom pages. `configuration.md` is the
   *Configuration* tab: `.runners-bar` format, storage locations, settings.

Signing is optional (variables `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`,
see https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

## Regenerating the screenshots

```
./gradlew runIde -Pscreenshots=marketplace/screenshots
```

Copies `marketplace/demo-project` to `/Users/Shared/demo-shop` (so no user path shows up in the
images), opens it in the sandbox, starts `dev` and writes seven PNGs to `marketplace/screenshots/`
(bar, tab menu, groups, add, bar at the top, settings page, `.runners-bar` with gutter icons). The IDE paints its own window for
this, no screen recording permission is needed. The helper lives in `src/screenshot/` and is only compiled with
`-Pscreenshots`, never into a release.

Requirements: Node at `/usr/local/bin/node` (hard-coded in the demo run configurations) and
`/Users/Shared/demo-shop` as a trusted project in the sandbox. If the sandbox asks on the first
run, confirm "Trust Project" once and run the command again.

## How it works

There is no official extension point for the area above the status bar or below the toolbar. The
plugin therefore wraps a part of the frame in its own container with the bar on top: the status bar
(position *bottom*) or the central area with editor and tool windows (position *top*). If the IDE
re-inserts that part (e.g. in presentation mode), the bar attaches itself again automatically. When
the plugin is unloaded, the original state is restored.

| File | Contents |
| --- | --- |
| `RunnersBarInstaller.kt` | Startup activity, attaching above the status bar, visibility |
| `RunnersBarService.kt` | Project service, persistence, entries; `RunnersBarSharedService` for shared groups (`.idea/runnersBar.xml`) |
| `RunnersBarState.kt` | Persisted state (personal and shared groups, entries, per-project values) |
| `RunnersBarPanel.kt` | The bar, the bar menu with its submenus, and drag and drop |
| `RunnersBarTab.kt` | Tab with click-to-run and menu |
| `AddToRunnersBarAction.kt` | *Add to Runners Bar...* in the context menu and the gutter run icon's menu |
| `VsCodeTasks.kt` | Reads `.vscode/tasks.json` (JSON with comments) and translates tasks into commands |
| `RunnersBarFile.kt` | Reads `.runners-bar` and keeps its groups and run configurations in sync |
| `VsCodeTasksImport.kt` | Import dialog, notification on project open, *Import VS Code Tasks...* action |
| `RunnersBarExecution.kt` | Starting (run/debug) and detecting running processes |
| `RunnersBarSettings.kt` | Defaults (position, tab and control alignment, menu arrow) and the settings page with per-project overrides |
| `ToggleRunnersBarAction.kt` | Toggles in *View \| Appearance* (global and per project) |
| `RunnersBarBundle.kt` | UI texts (English, German) in `resources/messages/` |

## License

Apache License 2.0, see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
Copyright 2026 Peter Heck (heck\it). The name and logo are not covered by the license.
