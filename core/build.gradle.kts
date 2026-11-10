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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // -PbenchRelease: instrumented tests (HttpClientBenchmark) on the release build type, which isn't debuggable,
    // so the code runs compiled as in the shipped app instead of mostly interpreted.
    if (providers.gradleProperty("benchRelease").isPresent) {
        testBuildType = "release"
        buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
    // HttpClientBenchmark: OkHttp next to HttpURLConnection on a device (see tools/http-bench-server.mjs).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
}
