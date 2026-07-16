import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.firebase.crashlytics")
    id("androidx.baselineprofile")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun releaseProperty(gradleName: String, environmentName: String): String? =
    providers.environmentVariable(environmentName)
        .orElse(providers.gradleProperty(gradleName))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

val releaseStoreFile = releaseProperty("hermesReleaseStoreFile", "HERMES_RELEASE_STORE_FILE")
val releaseStorePassword = releaseProperty("hermesReleaseStorePassword", "HERMES_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseProperty("hermesReleaseKeyAlias", "HERMES_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseProperty("hermesReleaseKeyPassword", "HERMES_RELEASE_KEY_PASSWORD")
val releaseSigningValues = mapOf(
    "HERMES_RELEASE_STORE_FILE" to releaseStoreFile,
    "HERMES_RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "HERMES_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "HERMES_RELEASE_KEY_PASSWORD" to releaseKeyPassword,
)
val releasePackagingRequested = gradle.startParameter.taskNames.any { qualifiedName ->
    val taskName = qualifiedName.substringAfterLast(':')
    taskName in setOf("assemble", "bundle", "build", "buildNeeded", "buildDependents") ||
        (taskName.contains("Release") &&
            listOf("assemble", "bundle", "package", "publish").any(taskName::startsWith))
}

if (releasePackagingRequested) {
    val missingValues = releaseSigningValues.filterValues { it == null }.keys
    check(missingValues.isEmpty()) {
        "Release packaging requires signing credentials: ${missingValues.joinToString()}. " +
            "Set environment variables or the matching hermesRelease* Gradle properties."
    }
    check(file(requireNotNull(releaseStoreFile)).isFile) {
        "Release keystore does not exist: $releaseStoreFile"
    }
}

android {
    namespace = "au.com.chrismckechnie.hermesmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "au.com.chrismckechnie.hermesmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    signingConfigs {
        if (releaseSigningValues.values.all { it != null }) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-installations")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.composables:icons-lucide:1.1.0")

    baselineProfile(project(":baselineprofile"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}

baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}
