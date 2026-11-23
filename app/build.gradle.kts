plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions bu iki değeri otomatik verir. Yerelde verilmezse varsayılanlar kullanılır.
val buildNumber: Int = (providers.gradleProperty("buildNumber").orNull ?: "1").toInt()
val ghRepo: String = providers.gradleProperty("ghRepo").orNull ?: ""

android {
    namespace = "com.example.crossup"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.crossup"
        minSdk = 30
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"

        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        buildConfigField("String", "GH_REPO", "\"$ghRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("crossup") {
            storeFile = file("crossup.jks")
            storePassword = "crossup123"
            keyAlias = "crossup"
            keyPassword = "crossup123"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("crossup")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("crossup")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
