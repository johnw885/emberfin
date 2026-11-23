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
}

dependencies {
    implementation(project(":core:data"))
    api(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
}
