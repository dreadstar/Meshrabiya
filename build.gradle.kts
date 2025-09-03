android {
    compileSdk = 36
    namespace = "com.github.UstadMobile.Meshrabiya"
}

plugins {
    id("com.android.library") version "8.12.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
}

// val buildConfigProperties = java.util.Properties()
// val buildConfigFile = project.file("buildconfig.local.properties")
// if (buildConfigFile.exists()) {
//     buildConfigFile.inputStream().use { stream: java.io.InputStream -> buildConfigProperties.load(stream) }
// }

group = "com.github.UstadMobile.Meshrabiya"
version = "0.1d11-snapshot"

ext {
    set("version_androidx_core", "1.17.0")
    set("version_appcompat", "1.7.1")
    set("version_datastore", "1.1.7")
    set("version_android_test_ext_junit", "1.3.0")
    set("version_androidx_test_rules", "1.7.0")
    set("version_android_junit_runner", "1.6.0")
    set("version_androidx_orchestrator", "1.6.1")
    set("version_kotlin_mockito", "5.4.0")
    set("version_kotlinx_serialization", "1.9.0")
    set("version_coroutines", "1.10.2")
    set("version_junit", "5.13.4")
    set("version_android_mockito", "5.18.0")
    set("version_turbine", "1.2.1")
    set("version_mockwebserver", "4.12.0")
    set("version_okhttp", "4.12.0")
    set("version_rawhttp", "2.6.0")
    set("version_bouncycastle", "1.75")
    set("version_android_desugaring", "2.1.5")
    set("version_ip_address", "5.5.1")
    set("version_compose_bom", "2023.06.01")
    set("version_compose_accompanist", "0.33.0-alpha")
    set("version_code_scanner", "2.3.2")
    set("version_navigation", "2.5.3")
    set("version_kodein_di", "7.20.2")
    set("version_zxing_embedded", "4.3.0")
    set("version_acra", "5.11.0")
    set("version_android_lifecycle", "2.6.1")
    set("version_android_activity", "1.7.2")
    set("version_nanohttpd", "2.3.1")
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    // Add other dependencies as needed
}

// ...existing code...
