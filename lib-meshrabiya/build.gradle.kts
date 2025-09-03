tasks.withType<Test> {
    val testFilter = project.findProperty("testFilter") as String?
    if (testFilter != null) {
        filter {
            includeTestsMatching(testFilter)
        }
    }
}
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("maven-publish")
}

android {
    testOptions {
        unitTests.all {
            // No test filtering here; handled by Test task above
        }
    }
    namespace = "com.ustadmobile.meshrabiya"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    // ...existing code...

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    publishing {
        singleVariant("release") {
            // Optional: further configuration
        }
        singleVariant("debug") {
            // Optional: further configuration
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.rawhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.datastore)

    implementation(libs.bouncycastle)

    implementation(libs.ipaddress)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)

    implementation(libs.mockito.kotlin)
    implementation(libs.turbine)
    implementation(libs.mockwebserver)
    implementation(libs.okhttp)

    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockk)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = rootProject.group.toString()
            artifactId = project.name
            version = rootProject.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
