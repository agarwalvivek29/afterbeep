import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ksProps = Properties().also { p ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { input -> p.load(input) }
}

android {
    namespace = "dev.afterbeep"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.afterbeep"
        minSdk = 33          // call-interception APIs + CALL_SCREENING mode
        targetSdk = 36
        versionCode = 21
        versionName = "1.0.0"
        // Every phone it targets (Android 13+, OnePlus 13s / Nord CE 2 Lite) is 64-bit ARM.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release key lives outside git (keystore/ + keystore.properties). Back it up: every future update must be
    // signed with the same key or it won't install over the old version.
    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ksProps.isNotEmpty()) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // In-app wireless-debugging pairing + adb shell (starts the shell-uid engine)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    testImplementation("junit:junit:4.13.2")
}
