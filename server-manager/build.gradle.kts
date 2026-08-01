plugins {
    java
    application
}

dependencies {
    implementation(project(":shared"))
    implementation("io.javalin:javalin:6.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.18")
}

application {
    mainClass.set("dev.haraguro.modserverplaymanager.servermanager.ServerManagerApp")
}

// Gradle's `run` (a JavaExec) does NOT forward -D flags from the `./gradlew`
// command line to the forked JVM by default — only to the Gradle process
// itself. Without this, `./gradlew :server-manager:run -Dmcsync....=...`
// silently starts server-manager with defaults instead. Keep this list in
// sync with every System.getProperty("mcsync....") call in this module.
tasks.named<JavaExec>("run") {
    listOf(
        "mcsync.server.dir",
        "mcsync.manager.port",
        "mcsync.manager.api.key",
        "mcsync.manager.autostart",
        "mcsync.rcon.port",
        "mcsync.rcon.autoconfigure",
        "mcsync.server.jvm.minMemory",
        "mcsync.server.jvm.maxMemory",
        "mcsync.backup.interval.minutes",
        "mcsync.backup.retention.count",
        "mcsync.backup.dir",
        "mcsync.manager.crash.maxRestarts",
        "mcsync.manager.crash.windowMinutes",
        "mcsync.portforward.enabled",
        "mcsync.portforward.syncserver.port",
        "mcsync.portforward.syncserver.apikeyset",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// Packages server-manager as a standalone native app (bundled JRE, no local
// Java/Gradle needed) — mirrors launcher/build.gradle.kts' jpackageAppImage.
// Produces build/jpackage/ModServerPlayManagerByHaraguroService/ for the OS
// this task runs on — jpackage doesn't cross-compile, so build on each target platform.
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

    executable = jpackageExecutable.absolutePath
    args = buildList {
        add("--type"); add("app-image")
        add("--input"); add(libDir.get().asFile.absolutePath)
        add("--dest"); add(outputDir.get().asFile.absolutePath)
        add("--name"); add("ModServerPlayManagerByHaraguroService")
        add("--app-version"); add(appVersion)
        add("--vendor"); add("ModServerPlayManagerByHaraguro Project")
        add("--main-jar"); add(tasks.jar.get().archiveFileName.get())
        // Console app — no GUI entry point, unlike launcher's jpackageAppImage.
        add("--main-class"); add("dev.haraguro.modserverplaymanager.servermanager.ServerManagerApp")
        add("--win-console")
    }
}
