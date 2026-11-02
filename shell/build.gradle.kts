plugins {
    alias(libs.plugins.android.library)
}

// The built-in shell backend: pairs with Android's Wireless debugging on this phone, talks the adb
// protocol to the phone's own adbd, and starts a helper process as the shell user (see ShellServer).
android {
    namespace = "com.lanqi.weiassist.shell"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    buildFeatures {
        aidl = true
        prefab = true
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.conscrypt.android)
    implementation(libs.boringssl)
    testImplementation(libs.junit)
}
