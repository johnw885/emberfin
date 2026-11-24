import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Collects every dependency's licence at build time for Settings → About (res/raw/aboutlibraries.json).
    alias(libs.plugins.aboutlibraries.android)
}

// DEV_SERVER_URL lives in the git-ignored local.properties and only reaches debug builds,
// so the server address never lands in git or in a release APK.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val devServerUrl: String = localProps.getProperty("DEV_SERVER_URL") ?: ""

android {
    namespace = "com.johnw.reelfin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.johnw.reelfin"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_SERVER_URL", "\"$devServerUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            // Sideload-only for now: sign release builds with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

// No network during builds: licence texts come from the plugin's bundled SPDX set.
aboutLibraries {
    offlineMode = true
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
}
