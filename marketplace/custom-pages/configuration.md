## Project file .runners-bar

JSON with comments in the project root. Each runner becomes a Shell Script run configuration in its group. The file is reloaded on project open and on every change.

```jsonc
{
  "groups": [
    {
      "name": "Dev",
      "runners": [
        { "name": "Start", "command": "npm run dev" },
        { "name": "Tests", "command": "npm test", "terminal": false },
        { "name": "API", "command": "php artisan serve", "cwd": "backend",
          "env": { "APP_ENV": "local" } }
      ]
    }
  ]
}
```

| Key | Type | Default |
| --- | --- | --- |
| `groups[].name` | string | default group |
| `runners[].command` | string, required | – |
| `runners[].name` | string | the command |
| `runners[].cwd` | path relative to the project root | project root |
| `runners[].env` | object of strings | – |
| `runners[].terminal` | boolean: terminal or Run window | detected from the command (`ssh`, DB clients, `read`, `-it` → terminal) |

The file is authoritative. Changing a runner updates its configuration, and removing a runner deletes it. Runners without a terminal run in the Run window, so Restart and Stop work.

Configurations are named `Name (Group)` (e.g. `Sandbox (Plugin)`), so equal names in several groups stay apart in the IDE's run configurations. Inside its group the tab shows just `Name`; this applies to every tab whose name ends in ` (Group)` of the group shown.

## Storage

| Scope | Location | VCS |
| --- | --- | --- |
| Personal tabs and groups, per-project overrides | `.idea/workspace.xml` | no |
| Shared groups | `.idea/runnersBar.xml` (+ `.idea/runConfigurations/` for configurations they need) | yes |
| Runners from the project file | `.runners-bar` | yes |
| IDE-wide defaults | `options/runnersBar.xml` | – |

Shared groups reload when the file changes, e.g. after `git pull`. To share a group or stop sharing it, use the group switcher or the checkbox in the settings.

## Settings

**Settings | Appearance & Behavior | Runners Bar**

**Appearance**: *All projects* sets the default. *This project* overrides it or is set to *Same as all projects*.

| Option | Values |
| --- | --- |
| Runners Bar | Shown / Hidden |
| Position | Top (below the toolbar) / Bottom (above the status bar) |
| Tabs | Left / Center / Right |
| Controls (bar menu, group switcher) | Left / Right |
| Menu arrow on tabs | Shown / Hidden (the tab menu is always available with a right click) |
| Menu button | Play arrow in a white pill / Play arrow, filled / Play arrow, outline / Plus (all projects only) |

**Groups in *project***: *Share with the team* per group. Shared groups are moved to `.idea/runnersBar.xml`.

**VS Code tasks / DDEV**: offer an import when a project opens. Both are on by default.

**Run Anything (All Projects)**: prefix, default `bar`.

## Imports

- **`.vscode/tasks.json`** (root or a direct subfolder): `shell`, `process`, `npm`, `gulp`, `grunt`, `jake`, `docker-build`, `docker-compose`, plus `${workspaceFolder}`, `${env:…}` and the `osx`/`linux`/`windows` variants. Tasks that need `${file}`, `${input:…}` or only `dependsOn` are skipped. Imports run again idempotently.
- **DDEV** (`.ddev/config.yaml`): imports the standard `ddev` commands and `.ddev/commands/*/` into the group *DDEV*.

## Run Anything

`bar <command>` creates a configuration, adds it to the bar and runs it. If a provider recognises the command (npm, Gradle, Maven, …), its configuration type is used. Otherwise the command becomes a Shell Script configuration.
