plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

dependencies {
    implementation(project(":shared"))
    // Lets LauncherMain dispatch straight into ServerManagerApp/SyncServerApp
    // when re-invoked with --server-manager/--sync-server, so the packaged
    // app is a single exe — see LauncherMain.
    implementation(project(":server-manager"))
    implementation(project(":sync-server"))
}

application {
    // Console entry point — used for `:launcher:run` and IDE debugging so
    // dev/CI work doesn't need a display. The packaged distributable
    // (jpackageAppImage) targets the GUI's LauncherMain instead; see below.
    mainClass.set("dev.haraguro.modserverplaymanager.launcher.Launcher")
}

javafx {
    version = "26.0.2"
    modules = listOf("javafx.controls")
}

// See sync-server/build.gradle.kts — `./gradlew run` doesn't forward -D
// flags to the forked JVM by default, so -Dmcsync.downloadAssets=false
// would otherwise silently do nothing.
tasks.named<JavaExec>("run") {
    System.getProperty("mcsync.downloadAssets")?.let { systemProperty("mcsync.downloadAssets", it) }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "dev.haraguro.modserverplaymanager.launcher.Launcher",
            // Read at runtime via Package.getImplementationVersion() (see
            // dev.haraguro.modserverplaymanager.launcher.update.AppVersion) to compare against
            // GitHub Releases' latest tag. Only present when running from
            // the built jar (jpackage), not `:launcher:run`'s loose classes
            // — AppVersion treats that as "dev" and skips the update check.
            "Implementation-Version" to project.version
        )
    }
}

// Prints server/whitelist.json entries for offline-mode usernames — see
// WhitelistGenerator's javadoc. Names come via -P since this is a plain
// JavaExec task, not the `application` plugin's `run` (which is pinned to
// the console Launcher and only accepts args via its own --args flag).
tasks.register<JavaExec>("generateWhitelist") {
    group = "application"
    description = "Prints whitelist.json entries: ./gradlew :launcher:generateWhitelist -PwhitelistNames=alice,bob"
    mainClass.set("dev.haraguro.modserverplaymanager.launcher.tools.WhitelistGenerator")
    classpath = sourceSets["main"].runtimeClasspath
    args = (providers.gradleProperty("whitelistNames").orNull ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

// Packages the launcher as a standalone native app (bundled JRE, no local
// Java/Gradle needed) so it can actually be handed to server participants.
// Produces build/jpackage/ModServerPlayManagerByHaraguro/ for the OS this
// task runs on — jpackage doesn't cross-compile, so build on each target platform.
val jpackageOutputDir = layout.buildDirectory.dir("jpackage")

// jpackage refuses to run if its app-image subdirectory already exists; a
// dedicated Delete task (configured eagerly) avoids the execution-time
// `project.delete(...)` deprecation a doFirst block would trigger.
val cleanJpackageOutput = tasks.register<Delete>("cleanJpackageOutput") {
    delete(jpackageOutputDir)
}

tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "Builds a standalone native app image (bundled JRE) via jpackage."
    dependsOn("installDist", cleanJpackageOutput)

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val jpackageExecutable = File(System.getProperty("java.home"), "bin/" + if (isWindows) "jpackage.exe" else "jpackage")
    val libDir = layout.buildDirectory.dir("install/${project.name}/lib")
    val outputDir = jpackageOutputDir
    // jpackage's --app-version rejects SNAPSHOT-style qualifiers.
    val appVersion = project.version.toString().substringBefore("-").ifBlank { "0.1.0" }

    inputs.dir(libDir)
    outputs.dir(outputDir)

    doFirst {
        outputDir.get().asFile.mkdirs()
    }

    // Opt-in: `./gradlew :launcher:jpackageAppImage -PdebugPort=5005` bakes a
    // JDWP agent into the app image's launcher .cfg (jpackage app-image exes
    // don't forward arbitrary CLI flags as JVM options, only as program
    // args, so this is the only way to make a *built* exe attachable).
    // suspend=y so the process waits at startup for a debugger — see
    // .vscode/launch.json's "Attach to packaged exe" — instead of racing
    // whoever runs the exe to attach before anything interesting happens.
    // Never set for `releaseZip`'s normal build: that would ship every
    // player a listening debug port.
    val debugPort = providers.gradleProperty("debugPort").orNull
    inputs.property("debugPort", debugPort ?: "")

    executable = jpackageExecutable.absolutePath
    args = buildList {
        add("--type"); add("app-image")
        add("--input"); add(libDir.get().asFile.absolutePath)
        add("--dest"); add(outputDir.get().asFile.absolutePath)
        add("--name"); add("ModServerPlayManagerByHaraguro")
        add("--app-version"); add(appVersion)
        add("--vendor"); add("ModServerPlayManagerByHaraguro Project")
        add("--main-jar"); add(tasks.jar.get().archiveFileName.get())
        // GUI entry point, not the console Launcher — the packaged app that
        // goes to server participants gets a window, not a console.
        // LauncherMain (not LauncherApp itself) is deliberate: a
        // classpath-launched (non-modular) JavaFX main class refuses to
        // start with "JavaFX runtime components are missing" if it directly
        // extends Application — an indirection class that just calls
        // Application.launch() sidesteps that check.
        add("--main-class"); add("dev.haraguro.modserverplaymanager.launcher.gui.LauncherMain")
        // jpackage's default jlink options include --strip-native-commands,
        // which drops runtime/bin/java(.exe) from the bundled runtime image.
        // LaunchCommandBuilder needs that binary to spawn the actual
        // Minecraft client process (a separate classpath/main-class/JVM args
        // from this launcher's own), so keep everything else jpackage would
        // normally strip but omit --strip-native-commands.
        add("--jlink-options"); add("--strip-debug --no-man-pages --no-header-files")
        if (debugPort != null) {
            add("--java-options"); add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$debugPort")
        }
    }
}

// Zips jpackageAppImage's output for uploading as a GitHub release asset —
// see root README "Releasing a new launcher version".
tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Zips the app image for a GitHub release: build/distributions/ModServerPlayManagerByHaraguro-<version>-windows.zip"
    dependsOn("jpackageAppImage")

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val platform = if (isWindows) "windows" else if (System.getProperty("os.name").lowercase().contains("mac")) "mac" else "linux"

    archiveBaseName.set("ModServerPlayManagerByHaraguro")
    archiveVersion.set(project.version.toString().substringBefore("-"))
    archiveClassifier.set(platform)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    // jpackageOutputDir already contains a single ModServerPlayManagerByHaraguro/
    // subfolder (from --dest + --name), so this zips straight to
    // <zip root>/ModServerPlayManagerByHaraguro/... with no extra nesting.
    from(jpackageOutputDir)
}
