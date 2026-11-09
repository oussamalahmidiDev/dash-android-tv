pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "FrostWire release artifacts"
            url = uri("https://github.com/frostwire/frostwire-jlibtorrent/releases/download")
            patternLayout { artifact("release/[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.frostwire") }
        }
    }
}
rootProject.name = "DASH"
include(":app")
