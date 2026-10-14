plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kg.forester.sg"
    compileSdk = 34

    defaultConfig {
        applicationId = "kg.forester.sg"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "0.9-beta11"
        // head units and phones are ARM; the x86 copies of the native speech libraries only made the APK bigger
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release key lives outside the repo: SUBARU_RELEASE_STORE / _PASSWORD / _ALIAS in ~/.gradle/gradle.properties.
    // Without it (e.g. someone building from source) the debug key is used.
    val releaseStore = (project.findProperty("SUBARU_RELEASE_STORE") as String?)?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = project.property("SUBARU_RELEASE_PASSWORD") as String
            keyAlias = project.property("SUBARU_RELEASE_ALIAS") as String
            keyPassword = project.property("SUBARU_RELEASE_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("com.composables:icons-lucide:1.1.0")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // offline speech synthesis for the warnings (k2-fsa sherpa-onnx, Apache-2.0; ARM-only copy of the v1.13.8 AAR)
    implementation(files("libs/sherpa-onnx-1.13.8-arm.aar"))
    // unpacking the downloaded voice (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.21")
    testImplementation("junit:junit:4.13.2")
}
