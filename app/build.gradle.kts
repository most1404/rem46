plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.yadavar.shamsi"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.yadavar.shamsi"
        minSdk = 26
        targetSdk = 35
        // Minutes since 1970: every new build gets a higher versionCode than any earlier build,
        // so a new APK can always be installed over the old one.
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
        versionName = "1.0.17"
        // The shared-groups server address and its PUBLIC anon key are given at build time (GitHub Actions secrets),
        // never written in the repository. The service_role key must never be used here.
        fun cfg(name: String): String =
            (System.getenv(name) ?: (project.findProperty(name) as String?) ?: "").trim().replace("\"", "")
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY")}\"")
        // sherpa-onnx ships native libraries for four CPU types; real phones only need these two.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // One fixed signing key for every build. Builds signed with different keys cannot update each other,
    // which is what forced uninstalling the app before installing a new version.
    signingConfigs {
        create("yadavar") {
            storeFile = file("yadavar.jks")
            storeType = "pkcs12"
            storePassword = "yadavar123"
            keyAlias = "yadavar"
            keyPassword = "yadavar123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("yadavar")
        }
        release {
            signingConfig = signingConfigs.getByName("yadavar")
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // One copy of each native library, even if two dependencies bring the same file.
            pickFirsts += listOf("**/libonnxruntime.so", "**/libsherpa-onnx-jni.so")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The Android SDK only has stubs of org.json for local tests; this is the real library.
    testImplementation("org.json:json:20240303")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime)
    implementation(libs.datastore)
    implementation(libs.core.ktx)
    implementation(libs.serialization.json)
    // Lottie animations (tick, empty list, ringing bell), fingerprint lock.
    implementation(libs.lottie)
    implementation(libs.fragment)
    implementation(libs.biometric)
    // Offline Persian speech recognition (Shenava model). The exclusion avoids a duplicate JVM-only copy of the same classes.
    implementation(libs.sherpa.onnx) {
        exclude(group = "com.github.k2-fsa.sherpa-onnx")
    }
}
