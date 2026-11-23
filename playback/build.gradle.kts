plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.johnw.reelfin.playback"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Plain-Kotlin unit tests; android.util.Log calls return defaults instead of throwing.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(project(":core:data"))
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    api(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
