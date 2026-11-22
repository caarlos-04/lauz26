import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// LLM keys for the chat on Android (docs/ai.md, "Android"). DEBUG builds only, for testing on our own phones: the key
// is read at build time from an environment variable or the git-ignored repo-root .env, and ends up INSIDE the APK,
// so a debug APK built with a key must never be shared. Release builds never get a key (canned answers).
val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }

fun secret(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotEnv[name].orEmpty()

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val llmSettings = listOf("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "TWIN_LLM_MODEL")

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.lauzhack.twin"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lauzhack.twin"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            llmSettings.forEach { buildConfigField("String", it, quoted(secret(it))) }
        }
        release {
            llmSettings.forEach { buildConfigField("String", it, quoted("")) }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}