plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Compile-time stubs copied from keiyoushi/extensions-lib 1.4.2 (vendor/extlib).
// They must never end up inside the APK: the host app provides the real ones.
android {
    namespace = "eu.kanade.tachiyomi.libstub"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        "../vendor/extlib/library/src/main/java",
    )
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.11")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jsoup:jsoup:1.15.3")
    compileOnly("androidx.preference:preference-ktx:1.2.1")
}
