pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") // fabric-loom
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "ModServerPlayManagerByHaraguro"

// Code workspace only. The `server/` directory is a standalone runtime
// environment (an actual Fabric server install) and is intentionally NOT
// a Gradle module — see server/README.md.
include("shared")
include("mod")
include("launcher")
include("sync-server")
include("server-manager")
