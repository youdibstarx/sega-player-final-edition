import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.segaiptv.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.segaiptv.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "0.5.14"
        // Android Emulator: 10.0.2.2; physical device: your PC LAN IP / domain.
        val backendUrl = providers.gradleProperty("SEGA_IPTV_BACKEND_URL")
            .orElse("https://sega.faster4k.xyz").get()
        require(URI(backendUrl).scheme == "https") { "Panel URL must use HTTPS" }
        // Obfuscation only: no URL encryption key or shared authentication secret in the APK.
        val encoded = backendUrl.toByteArray(Charsets.UTF_8).mapIndexed { i, b ->
            (b.toInt() and 255) xor ((i * 31 + 93) and 255)
        }.joinToString(",")
        buildConfigField("int[]", "BACKEND_BYTES", "new int[]{$encoded}")
        buildConfigField("boolean", "DEVICE_PROOF", "false")
        buildConfigField("boolean", "ENFORCE_OWNER_SIGNATURE", "false")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".v3.preview"
            versionNameSuffix = "-preview"
        }
    }
    buildTypes {
        getByName("release") {
            applicationIdSuffix = ".v3"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEVICE_PROOF", "true")
        }
        create("hardened") {
            initWith(getByName("release"))
            // Independent installation. Sign externally with the persistent owner key.
            applicationIdSuffix = ".v3.secure"
            versionNameSuffix = "-secure"
            signingConfig = null
            buildConfigField("boolean", "ENFORCE_OWNER_SIGNATURE", "true")
            matchingFallbacks += listOf("release")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}





