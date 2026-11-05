plugins {
    alias(libs.plugins.android.library)
}

// DeepSeek Harness as an AI core: DshEngine talks to dsh (run in Termux, see dsh-host/) over a loopback socket.
android {
    namespace = "com.lanqi.weiassist.dsh"
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
    api(project(":core"))
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
}
