import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.dokka)
}

// Single source of truth for the SDK version: shared between the published Maven artifact
// coordinates below and the runtime BuildConfig.SDK_VERSION constant the device-info
// collector reports to the backend.
val sdkVersion = "0.1.0"

android {
    namespace = "com.triggerbee.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Required by Robolectric — gives it access to compiled Android resources
        // (R.* values, manifests) the runtime needs to fake an Android environment.
        unitTests.isIncludeAndroidResources = true
    }
}

// AGP 9 owns Kotlin compilation; the `kotlinOptions { }` block on android { } is gone,
// so configure the Kotlin compiler via the kotlin extension instead.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// Maven Central publication. The gradle project is named `sdk` but the published artifact is
// `triggerbee-android` — set the coordinates explicitly so the on-disk module name doesn't
// leak into the artifact id consumers see. SNAPSHOT versions go to Sonatype OSS snapshots;
// non-SNAPSHOT versions go through the Central Portal release flow. Signing keys + Sonatype
// credentials are supplied from the publishing environment (CI secrets / ~/.gradle/gradle.properties).
mavenPublishing {
    coordinates(
        groupId = "com.triggerbee",
        artifactId = "triggerbee-android",
        version = sdkVersion,
    )
    publishToMavenCentral()
    signAllPublications()

    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    pom {
        name.set("Triggerbee Android SDK")
        description.set(
            "Native Android SDK for the Triggerbee platform. Wraps the public REST API and " +
                "provides a Compose widget primitive for rendering campaigns inside a WebView."
        )
        url.set("https://github.com/TriggerbeeIO/triggerbee-sdk-android")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("triggerbee")
                name.set("Triggerbee AB")
                email.set("support@triggerbee.com")
                organization.set("Triggerbee AB")
                organizationUrl.set("https://triggerbee.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/TriggerbeeIO/triggerbee-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/TriggerbeeIO/triggerbee-sdk-android.git")
            url.set("https://github.com/TriggerbeeIO/triggerbee-sdk-android")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/TriggerbeeIO/triggerbee-sdk-android/issues")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.androidx.datastore.preferences)

    // Compose surface (TriggerbeeWidgetView). Kept minimal — only `ui`, `foundation`, and
    // `runtime`; consumers using non-Compose UI still get a working AAR because the Composable
    // is opt-in (a lazy reference). Material is not pulled in by the SDK.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Gradle 9 stopped bundling the JUnit Platform launcher on the test runtime classpath;
    // it must now be declared explicitly or the test JVM fails with "Failed to load JUnit Platform".
    testRuntimeOnly(libs.junit.platform.launcher)
    // Vintage engine runs JUnit 4 tests under the JUnit Platform — Robolectric still uses
    // its own @RunWith(RobolectricTestRunner::class) which is JUnit 4 only.
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.truth)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
