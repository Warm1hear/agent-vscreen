plugins {
    // 1.4: AGP 9 (built-in Kotlin) + Kotlin 2.4 + compileSdk 37, required by Compose 1.12 / miuix 0.9.
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // 1.4: page routes for miuix-nav are @Serializable keys (saved and restored with the back stack).
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
// Build outputs live on the root disk (the /data disk is nearly full).
allprojects { layout.buildDirectory.set(file("/srv/avs-build/${project.name}")) }
