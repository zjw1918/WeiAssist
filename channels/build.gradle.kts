plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lanqi.weiassist.channels"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Http: the app's shared HTTP client.
    implementation(project(":core"))
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
}
