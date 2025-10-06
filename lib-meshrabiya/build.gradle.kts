// NOTE: The parent Meshrabiya module uses build.gradle (Groovy format) for building,
// not build.gradle.kts. This lib-meshrabiya submodule uses build.gradle.kts.

plugins {
    id("com.android.library") version "8.12.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("jacoco")
}

android {
    namespace = "com.ustadmobile.meshrabiya"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isTestCoverageEnabled = true
        }
        getByName("release") {
            isMinifyEnabled = false
            isTestCoverageEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            // Configure test options for release builds
            testProguardFiles("test-proguard-rules.pro")
        }
    }
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            
            all {
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
                it.systemProperty("mockito.verbose", "true")
                // Disable periodic originating-message tasks during unit tests to avoid
                // noisy background schedulers and make unit tests deterministic.
                it.systemProperty("meshrabiya.enableOriginatingPeriodicTasks", "false")
                it.testLogging {
                    // Show when tests start, and show standard output/error so test runtime
                    // information (like "sending originating message" or socket startup)
                    // is visible during a run instead of leaving the build silent.
                    events("started", "passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    // LiteRT - Google's new TensorFlow Lite runtime
    implementation("com.google.ai.edge.litert:litert:2.0.0-alpha")
    implementation("com.google.ai.edge.litert:litert-gpu:2.0.0-alpha")
    implementation("com.google.ai.edge.litert:litert-support:2.0.0-alpha")

    // ML Kit - Using unbundled libraries (requires Google Play Services)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")

    // Additional Android dependencies that might be needed
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.gms:play-services-tasks:18.1.0")

    // Test dependencies - needed for both debug and release tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.10.3")

    // Ensure ByteBuddy is available for Mockito
    testImplementation("net.bytebuddy:byte-buddy:1.14.15")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.14.15")
}

// Configure JaCoCo test coverage for Meshrabiya module
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/testDebugUnitTest/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/testDebugUnitTest/jacoco.xml"))
    }
    
    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*"
    )
    
    val kotlinDebugTree = fileTree("${project.buildDir}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    
    classDirectories.setFrom(files(kotlinDebugTree))
    
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    
    executionData.setFrom(fileTree(project.buildDir) {
        include("outputs/unit_test_code_coverage/*/test*.exec")
    })
}


