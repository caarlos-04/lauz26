rootProject.name = "financial-twin-ui"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":composeApp")

// The future-self chat agent lives at the repo root (ai/), next to ui/, as in docs/README.md section 4.
include(":ai")
project(":ai").projectDir = file("../ai")
