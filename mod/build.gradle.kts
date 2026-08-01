plugins {
    // Minecraft 26.2 ships unobfuscated (Mojang dropped obfuscation as of
    // the 26.1 cycle), so this uses Loom's unobfuscated-version plugin
    // variant: no mappings, no remapping, no mod*/regular dependency split.
    // The classic `fabric-loom` id expects obfuscated MC + mappings and
    // will not work here. See https://github.com/FabricMC/fabric-loom/issues/1585
    // and https://github.com/FabricMC/fabric-loom/issues/1489.
    id("net.fabricmc.fabric-loom") version "1.17.17"
}

val minecraftVersion = project.property("minecraft_version") as String
val fabricLoaderVersion = project.property("fabric_loader_version") as String
val fabricApiVersion = project.property("fabric_api_version") as String

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")

    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // NOTE: the mod intentionally does NOT depend on :shared. Loom only
    // remaps/jars this module's own sourceSet, so a plain project
    // dependency would compile but throw NoClassDefFoundError at runtime.
    // launcher and sync-server are ordinary JVM apps and share :shared's
    // DTOs directly; the mod talks to sync-server over HTTP/JSON instead
    // (see dev.haraguro.modserverplaymanager.mod.api.ApiClient). If you need shared *classes*
    // bundled into the mod jar too, add the Shadow plugin and remap the
    // shaded jar — see Fabric's "bundling dependencies" docs.
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}
