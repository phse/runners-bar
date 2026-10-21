import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// Rechnerspezifische Einstellungen (nicht eingecheckt), z. B. platformLocalPath=/Applications/PhpStorm.app
val localProperties = Properties().apply {
    file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val platformLocalPath: String? = localProperties.getProperty("platformLocalPath")?.takeIf { file(it).exists() }

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

// ./gradlew runIde -Pscreenshots=<ordner> baut den Screenshot-Helfer mit ein (nie für Releases verwenden).
val screenshotDir = providers.gradleProperty("screenshots").orNull

sourceSets {
    main {
        if (screenshotDir != null) kotlin.srcDir("src/screenshot/kotlin")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion").get())
        }
        // Shell Script: optional, für „bar <Befehl>“ in Run Anything.
        bundledPlugin("com.jetbrains.sh")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>2.0.0</h3>
            <ul>
              <li><i>Add to Runners Bar...</i> in the context menu and in the menu of the gutter run icon: right-click a script in
                  <code>package.json</code>, a test or a main method, name it, done.</li>
              <li>VS Code tasks: import the tasks from <code>.vscode/tasks.json</code> (also from subfolders) as run configurations,
                  one group per folder. Pick the tasks you want and import the rest later. Supports shell, process, npm, gulp,
                  grunt, jake, docker-build and docker-compose tasks. A notification offers the import when a project opens;
                  it can be switched off in the settings.</li>
              <li>Move or copy tabs to another group: from the tab menu, or drag a tab out of the bar and drop it on a group.
                  Hold Option (macOS) or Ctrl to copy. The confirmation offers <i>Undo</i>.</li>
              <li><i>New Group…</i> is now directly in the + menu.</li>
              <li>The last active group is remembered reliably, also when it is a shared group.</li>
              <li>The bar keeps the same height with and without tabs.</li>
            </ul>
            <h3>1.4.0</h3>
            <ul>
              <li>Team groups: share a group with your team. It is stored in <code>.idea/runnersBar.xml</code>; commit the file
                  and everyone gets the same tabs. Its run configurations are stored as project files so they exist for everyone.
                  Share or stop sharing from the group switcher, the + menu's <i>Runners Bar</i> submenu or the settings.</li>
              <li>Redesigned settings page: one table with the defaults for all projects next to the values of the current project,
                  plus the project's groups with a <i>Share with the team</i> checkbox.</li>
            </ul>
            <h3>1.3.2</h3>
            <ul>
              <li>New plugin icon in the heck\it style.</li>
            </ul>
            <h3>1.3.1</h3>
            <ul>
              <li>Fixed a compatibility problem with 2026.1 when converting a shell script with a working directory.</li>
            </ul>
            <h3>1.3.0</h3>
            <ul>
              <li>Run Anything: <code>bar &lt;command&gt;</code> asks for a name first. Commands another provider understands
                  (npm scripts, Gradle, Maven, …) get that provider's configuration instead of a shell script;
                  shell scripts now run in the Run tool window instead of the terminal.</li>
              <li>Tab menu: <i>Duplicate…</i> copies a configuration, opens its editor and places it right behind the original.</li>
              <li>Tab menu: <i>Delete…</i> deletes the configuration completely (after a confirmation).</li>
              <li>Tab menu, <i>Shell</i> submenu: toggle <i>Execute in the Terminal</i> and convert a shell script
                  to the matching configuration (e.g. npm), keeping name, position and working directory.</li>
              <li>Move left / move right removed from the tab menu; use drag and drop.</li>
            </ul>
            <h3>1.2.0</h3>
            <ul>
              <li>Run Anything: <code>bar &lt;command&gt;</code> creates a shell script configuration for the command,
                  adds it to the bar and runs it. The prefix can be changed in the settings (for all projects).</li>
              <li>Temporary configurations (e.g. created by Run Anything) are listed in the + menu's
                  <i>Temporary Configurations</i> submenu, together with shell commands
                  recently run through Run Anything; adding one asks for a name and keeps it for good.</li>
              <li>Rename a configuration right from the tab menu.</li>
            </ul>
            <h3>1.1.0</h3>
            <ul>
              <li>The settings page now holds defaults for all projects; each project can override position,
                  alignment and the menu arrow (in the settings page or the + menu's <i>Runners Bar</i> submenu).</li>
              <li>Showing or hiding the bar in a project now overrides the default, so a single project can show
                  the bar while it is hidden everywhere else.</li>
              <li>Option to hide the menu arrow on the tabs; the menu stays available with a right click.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>Create new run configurations right from the + menu; they are added to the bar automatically.</li>
              <li>Position the bar at the top (below the toolbar) or at the bottom (above the status bar).</li>
              <li>Align the tabs left, center or right, and put the controls (+, group switcher) on the left or right.</li>
              <li>New settings page <i>Settings | Appearance &amp; Behavior | Runners Bar</i> and a
                  <i>Runners Bar</i> submenu in the + menu for groups, hiding, position and alignment.</li>
              <li>At the top, the bar continues the project color gradient of the toolbar.</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li>Hide the bar in a single project: <i>Hide in This Project</i> in the + menu or
                  <i>View | Appearance | Runners Bar in This Project</i>.</li>
              <li>Drag and drop like editor tabs: the tab follows the mouse, the other tabs make room,
                  and it turns red when dragged out of the bar to remove it. Escape cancels.</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>First release: run configurations as tabs above the status bar.</li>
              <li>Debug once, debug mode per tab, stop, settings, reorder and remove.</li>
              <li>Groups with switcher, drag and drop to reorder or remove.</li>
            </ul>
        """.trimIndent()
    }

    // Optional: Plugin signieren (Zertifikat siehe README). Ohne Umgebungsvariablen wird nicht signiert.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Für ./gradlew publishPlugin (erst nach dem ersten manuellen Upload möglich). Token aus der Umgebung
    // oder global aus ~/.gradle/gradle.properties (jetbrainsMarketplaceToken=…), gilt für alle eigenen Plugins.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("jetbrainsMarketplaceToken"))
    }

    // ./gradlew verifyPlugin prüft gegen die lokale IDE und die älteste unterstützte Version.
    pluginVerification {
        ides {
            platformLocalPath?.let { local(it) }
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyOldestVersion").get())
        }
    }
}

// Für Screenshots wird das Demo-Projekt an einen neutralen Ort kopiert, damit kein lokaler
// Benutzerpfad im Bild erscheint. Jeder Lauf startet mit dem Zustand aus marketplace/demo-project.
val screenshotProjectDir = "/Users/Shared/demo-shop"

val prepareScreenshotProject = tasks.register<Sync>("prepareScreenshotProject") {
    from("marketplace/demo-project")
    into(screenshotProjectDir)
}

// Apache 2.0 verlangt, dass LICENSE und NOTICE mit dem Plugin verteilt werden.
tasks.jar {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

// Ohne -Pscreenshots bleibt aus dem Build-Cache sonst ein leeres screenshot/-Verzeichnis in den Jars (auch im instrumentierten).
tasks.withType<Zip>().configureEach {
    includeEmptyDirs = false
}

tasks.runIde {
    // ./gradlew runIde -PopenProject=/pfad/zum/projekt öffnet direkt ein Projekt in der Sandbox.
    if (screenshotDir != null) {
        dependsOn(prepareScreenshotProject)
        args(screenshotProjectDir)
        jvmArgs("-Drunnersbar.screenshots=${file(screenshotDir).absolutePath}")
    } else {
        providers.gradleProperty("openProject").orNull?.let { args(it) }
    }
}
