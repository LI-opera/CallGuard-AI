plugins {
    id("org.jetbrains.kotlin.kapt")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.callguard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.callguard.app"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0"
    }

    buildFeatures { compose = true; buildConfig = true }
    defaultConfig { buildConfigField("boolean", "PERSIST_SCREENING_EVIDENCE", "false") }
    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-wp08"
            buildConfigField("boolean", "PERSIST_SCREENING_EVIDENCE", "true")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.room:room-runtime:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.55")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
