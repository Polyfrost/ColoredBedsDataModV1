plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

val minecraftVersion = property("minecraft_version") as String

version = property("mod_version") as String
base.archivesName = "ColoredBedsData"

repositories {
    maven("https://repo.hypixel.net/repository/Hypixel/")
    maven("https://api.modrinth.com/maven")
}

loom {
    runConfigs.remove(runConfigs["server"])
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.hypixel:mod-api:${property("hypixel_mod_api_version")}")
    localRuntime("maven.modrinth:hypixel-mod-api:1.0.2+build.1+mc26.1")
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
