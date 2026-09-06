plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.openlauncher.app"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId  = "com.openlauncher.app"
        minSdk         = 21
        targetSdk      = 36
        versionCode    = 6
        versionName    = "0.0.5"
        manifestPlaceholders["sharedUserId"] = ""
    }

    buildTypes {
        debug {
            // Default signing config for normal device testing (restores app visibility)
            manifestPlaceholders["sharedUserId"] = ""
        }
        // PiP beta build: joins android.uid.system so the reflective ActivityView /
        // freeform-windowing calls in PipWidget pass platform hidden-API + signature-permission
        // checks. Must be re-signed with the device's platform key post-build (see
        // .claude/keys/sign.sh) — gradle's own signing is irrelevant since apksigner replaces it.
        create("aospDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["sharedUserId"] = "android.uid.system"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["sharedUserId"] = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Network — weather
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.13.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compile-time-only declaration for android.app.TaskStackListener (@hide on
    // the public android.jar). Built by scripts/build-framework-stubs.sh from
    // framework-stubs/src; never packaged into the APK — at runtime this
    // resolves against the real hidden class in the device's boot image. See
    // TaskEmbedder in PipWidget.kt for the only place this is used.
    compileOnly(files("libs/framework-stubs.jar"))
}
