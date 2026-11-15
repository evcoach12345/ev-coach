import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 서명 = 저장소 밖 ~/.config/evcoach (실명 없는 «EV Coach» 키 · 비밀번호 파일 600)
val keyDir = File(System.getProperty("user.home"), ".config/evcoach")
val keyPass = File(keyDir, "keystore.pass").takeIf { it.exists() }?.readText()?.trim()

android {
    namespace = "app.evcoach"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.evcoach"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
    }
    signingConfigs {
        if (keyPass != null) create("release") {
            storeFile = File(keyDir, "release.jks")
            storePassword = keyPass
            keyAlias = "evcoach"
            keyPassword = keyPass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
