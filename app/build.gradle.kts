plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ravixKeystorePath = System.getenv("RAVIX_KEYSTORE_PATH")
val ravixKeystorePassword = System.getenv("RAVIX_KEYSTORE_PASSWORD")
val ravixKeyAlias = System.getenv("RAVIX_KEY_ALIAS")
val ravixKeyPassword = System.getenv("RAVIX_KEY_PASSWORD")

android {
    namespace = "net.ravix.chatoperator"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.ravix.chatoperator"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-internal"

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://socket.ravix.net\"")
        buildConfigField("String", "DEFAULT_SOCKET_PATH", "\"/socket.io\"")
    }

    signingConfigs {
        create("release") {
            if (!ravixKeystorePath.isNullOrBlank()) {
                storeFile = file(ravixKeystorePath)
                storePassword = ravixKeystorePassword
                keyAlias = ravixKeyAlias
                keyPassword = ravixKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (!ravixKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }
}
