pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "GroupStickerFix"
include(":app")
