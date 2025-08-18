// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25" apply false
}


val buildConfigProperties = java.util.Properties()
val buildConfigFile = project.file("buildconfig.local.properties")
if (buildConfigFile.exists()) {
    buildConfigFile.inputStream().use { buildConfigProperties.load(it) }
}

group = "com.github.UstadMobile.Meshrabiya"
version = "0.1d11-snapshot"

ext {
    set("version_androidx_core", "1.12.0")
    set("version_appcompat", "1.7.1")
    set("version_datastore", "1.1.7")
    set("version_android_test_ext_junit", "1.2.1")
    set("version_androidx_test_rules", "1.6.1")
    set("version_android_junit_runner", "1.5.2")
    set("version_androidx_orchestrator", "1.4.2")
    set("version_kotlin_mockito", "5.4.0")
    set("version_kotlinx_serialization", "1.6.3")
    set("version_coroutines", "1.8.1")
    set("version_junit", "4.13.2")
    set("version_android_mockito", "5.1.1")
    set("version_turbine", "0.12.1")
    set("version_mockwebserver", "4.10.0")
    set("version_okhttp", "4.10.0")
    set("version_rawhttp", "2.5.2")
    set("version_bouncycastle", "1.75")
    set("version_android_desugaring", "2.0.3")
    set("version_ip_address", "5.4.0")
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
