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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
        testImplementation("junit:junit:4.13.2")
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


