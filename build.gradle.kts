// Root build script. No plugins/sources of its own — see shared/, mod/, launcher/, sync-server/.

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("java_version").get().toInt()))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }
    }
}

// launcher/build.gradle.kts's own jpackageAppImage is now the single
// distributable: launcher depends on :server-manager and LauncherMain
// dispatches into ServerManagerApp when re-invoked with --server-manager
// (see LocalServiceLauncher), so one app image/one exe covers the
// "everything on one PC" case that used to need a separate merged task here
// (jpackage's --add-launcher, producing a second sibling exe). server-manager
// keeps its own standalone jpackageAppImage for a split-machine deployment —
// e.g. hosting server-manager alone on a headless VPS without JavaFX.
