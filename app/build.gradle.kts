import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lanqi.weiassist"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lanqi.weiassist"
        minSdk = 26
        targetSdk = 37
        // The release workflow sets these from the tag (-PversionName=0.2 -PversionCode=200).
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // -PdevTools=true: a release build with the developer tools of debug builds (the mock model, the test
        // intents), to measure performance as users get it. Never for builds that ship.
        buildConfigField("boolean", "DEV_TOOLS", (providers.gradleProperty("devTools").orNull == "true").toString())
    }

    // Release signing: keystore.properties (storeFile, storePassword, keyAlias, keyPassword) from the
    // WEIASSIST_SIGNING environment variable, else ~/.android/weiassist-release/. Without it, release
    // builds stay unsigned. The key never goes into this repository.
    val signing = File(System.getenv("WEIASSIST_SIGNING")
        ?: "${System.getProperty("user.home")}/.android/weiassist-release/keystore.properties")
    val releaseSigning = if (signing.isFile) signingConfigs.create("release") {
        val p = Properties().apply { signing.inputStream().use { load(it) } }
        storeFile = file(p.getProperty("storeFile"))
        storePassword = p.getProperty("storePassword")
        keyAlias = p.getProperty("keyAlias")
        keyPassword = p.getProperty("keyPassword")
    } else null

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            releaseSigning?.let { signingConfig = it }
            proguardFiles("proguard-rules.pro")
            if (providers.gradleProperty("profiling").orNull == "true") proguardFiles("proguard-profiling.pro")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Per-app language (Android 13+ Settings → App language): English default, Chinese.
    androidResources {
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Native libs are extracted at install (to nativeLibraryDir), not run from inside the APK: the AI gateway
    // (libmagpie.so) is a program, and Android runs an app's programs only from there.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":agent"))
    implementation(libs.androidx.browser)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
