pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // Both NewPipeExtractor and InnerTubeX are published through JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "bitchord-desktop"
