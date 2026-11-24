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
    namespace = "com.johnw.emberfin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.johnw.emberfin"
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
            // R8: shrink, optimise and obfuscate. The Baseline Profile in src/main/baseline-prof.txt
            // is rewritten by R8 to the obfuscated names and shipped in the APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            // Sideload-only for now: sign release builds with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release without R8, for generating the Baseline Profile: the ART profile dumped on the TV
        // must use the source class names (see baselineprofile/README.md).
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
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

// The Compose group mapping (deobfuscates Compose stack traces after R8) needs a tool that isn't
// cached here; Emberfin has no crash reporting to feed it, so it's off.
composeCompiler {
    includeComposeMappingFile = false
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
    // Installs the Baseline Profile on sideloaded and Play installs (Play also uses it at install time).
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
}
