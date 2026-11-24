# R8 rules for Emberfin release builds. Most libraries ship their own rules (kotlinx.serialization,
# Room, Media3, OkHttp, Koin needs none); only what they miss goes here.

# Readable crash traces: keep line numbers, hide the source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Jellyfin SDK logs through SLF4J / kotlin-logging and doesn't ship a binding.
-dontwarn org.slf4j.**
