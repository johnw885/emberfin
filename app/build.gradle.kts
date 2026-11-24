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

// Release signing comes from environment variables (CI sets them from GitHub secrets on a v* tag).
// Without them, release builds are signed with the debug key for sideloading.
val releaseKeystore: String? = providers.environmentVariable("EMBERFIN_KEYSTORE").orNull
// Version from the tag in CI (-PappVersionName=1.2.3 -PappVersionCode=10203), else the defaults below.
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.johnw.emberfin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.johnw.emberfin"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("EMBERFIN_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("EMBERFIN_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("EMBERFIN_KEY_PASSWORD").get()
            }
        }
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
            // The upload key when CI provides it (see .github/workflows/release.yml), else the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    lint {
        // Lint errors fail the build (and CI); warnings are reported only.
        abortOnError = true
        // Dependabot proposes dependency updates, so lint doesn't need to.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Also check :core:data, :core:ui and :playback from this module's report.
        checkDependencies = true
    }
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
