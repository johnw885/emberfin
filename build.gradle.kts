plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The source folder may sync to a cloud drive; keep Gradle build output outside it to avoid
// file locks and sync churn. Override with -PbuildRoot=...
val buildRoot: String = (findProperty("buildRoot") as String?)
    ?: "${System.getenv("USERPROFILE") ?: System.getProperty("user.home")}/ReelfinBuild"

allprojects {
    // Module paths like :core:data map to core-data so the two core modules don't collide.
    val dirName = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    layout.buildDirectory.set(file("$buildRoot/$dirName"))
}
