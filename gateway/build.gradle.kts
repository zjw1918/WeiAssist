import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// WeiAssist Gateway: a separate, optional app that runs the AI gateway (magpie, from :magpie) for this phone's
// apps and the devices on its network. Kept out of WeiAssist itself so the assistant stays small; WeiAssist finds
// it on 127.0.0.1:3425. Same version and release key as WeiAssist.
android {
    namespace = "com.lanqi.weiassist.gateway"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lanqi.weiassist.gateway"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1"
    }

    // The release key, found as in app/build.gradle.kts.
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
        release {
            releaseSigning?.let { signingConfig = it }
            proguardFiles("proguard-rules.pro")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        generateLocaleConfig = true
    }
    // Native libs are extracted at install (to nativeLibraryDir): libmagpie.so is a program, and Android runs an
    // app's programs only from there.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":magpie"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
