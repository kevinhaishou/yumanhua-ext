plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.kanade.tachiyomi.extension.zh.yumanhua"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.zh.yumanhua"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    compileOnly(project(":libstub"))
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.11")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jsoup:jsoup:1.15.3")
    compileOnly("androidx.preference:preference-ktx:1.2.1")
}
