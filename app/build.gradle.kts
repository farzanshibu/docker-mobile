plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is driven entirely by the environment so no key material or
// password ever lives in the repo. Set KEYSTORE_FILE, KEYSTORE_PASSWORD,
// KEY_ALIAS and KEY_PASSWORD to produce a signed release build; leave them
// unset and `assembleRelease` still works, it just emits an unsigned APK.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    ?: (project.findProperty("KEYSTORE_FILE") as String?)
val hasSigningKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.dockermobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dockermobile.app"
        minSdk = 26
        targetSdk = 35
        // CI overrides these from the release tag.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        // Only arm64 devices can host the embedded VM in v1.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                // v1 as well, so the APK installs on API 26-ish devices too.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasSigningKey) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // QEMU rides in jniLibs as libqemu_system_aarch64.so and is exec'd from
        // nativeLibraryDir. That only works if the installer actually unpacks
        // lib/ onto disk, i.e. extractNativeLibs=true.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commons.compress)
    implementation(libs.snakeyaml)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
