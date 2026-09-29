import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// release signing key lives outside the repo; local.properties (gitignored) points at a
// properties file with storeFile/storePassword/keyAlias/keyPassword. See README "Building".
val releaseSigning: Properties? = run {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val path = local.getProperty("shareplus.keystore") ?: return@run null
    val f = file(path)
    if (!f.exists()) null else Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.example.shareplus"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // store-safe id; the Kotlin/JNI package stays com.example.shareplus (namespace above)
        applicationId = "com.lakshithaonline.shareplus"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // this TV (JVC JV5524M) is armeabi-v7a/armeabi only -- see NOTES.md Phase 0.3
        ndk {
            abiFilters += "armeabi-v7a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        releaseSigning?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: native code finds NativeBridge/RaopCallbackHandler/onLog by name
            // via JNI, and the app is small enough that shrinking buys little
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        prefab = true
    }
}

dependencies {
    // UI is plain Android views (no Compose); these cover ComponentActivity, lifecycleScope
    // and ContextCompat
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // native: Oboe audio output + OpenSSL (Prefab AARs, see app/src/main/cpp/CMakeLists.txt)
    implementation(libs.oboe)
    implementation(libs.openssl)
}
