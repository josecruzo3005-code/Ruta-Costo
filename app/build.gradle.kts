plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "co.rutacosto.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "co.rutacosto.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        val orsApiKey = project.findProperty("ORS_API_KEY")?.toString() ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsApiKey\"")
    }
    signingConfigs {
        create("persistentDebug") {
            storeFile = rootProject.file(".ci-signing/rutacosto.keystore")
            storePassword = "rutacosto-local"
            keyAlias = "rutacosto"
            keyPassword = "rutacosto-local"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("persistentDebug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}