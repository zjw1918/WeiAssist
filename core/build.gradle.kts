plugins {
    alias(libs.plugins.android.library)
}

// The engine-neutral contract between the app and its AI cores (AgentCore, ToolHost, CoreRegistry).
// No engine lives here: each core is its own module (:core-pi, …) that implements this contract.
android {
    namespace = "com.lanqi.weiassist.core"
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
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
}
