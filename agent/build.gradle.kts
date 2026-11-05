plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lanqi.weiassist.agent"
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
    implementation(project(":core-pi"))
    implementation(project(":core-dsh"))
    api(project(":phone"))
    api(project(":shell"))
    api(project(":channels"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real library there.
    testImplementation(libs.org.json)
}
