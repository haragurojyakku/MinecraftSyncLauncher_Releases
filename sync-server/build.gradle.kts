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
    mainClass.set("dev.haraguro.modserverplaymanager.syncserver.SyncServerApp")
}

// Gradle's `run` (a JavaExec) does NOT forward -D flags from the `./gradlew`
// command line to the forked JVM by default — only to the Gradle process
// itself. Without this, `./gradlew :sync-server:run -Dmcsync.api.key=...`
// silently starts sync-server with no key configured at all.
tasks.named<JavaExec>("run") {
    listOf("mcsync.api.key", "mcsync.port", "mcsync.server.dir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
