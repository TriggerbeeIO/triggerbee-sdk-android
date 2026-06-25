// Top-level build file. All real config lives in the module build.gradle.kts files.
plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// ABI guard via binary-compatibility-validator is a 1.0 follow-up — the current 0.18.x plugin
// doesn't pick up AGP 9 / Gradle 9 Kotlin sources yet. While we're on 0.x.x the API is allowed
// to break between minor versions, so the guard is nice-to-have rather than blocking.
