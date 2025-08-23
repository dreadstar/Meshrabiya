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
    compileSdk = 33

    defaultConfig {
        minSdk = 26
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            // Optional: further configuration
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.athaydes.rawhttp:rawhttp-core:2.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.75")

    implementation("com.github.seancfoley:ipaddress:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")

    implementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")

    implementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    implementation("app.cash.turbine:turbine:0.12.1")
    implementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testImplementation("app.cash.turbine:turbine:0.12.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("app.cash.turbine:turbine:0.12.1")
    androidTestImplementation("org.mockito:mockito-android:5.1.1")
    androidTestImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.10")
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
