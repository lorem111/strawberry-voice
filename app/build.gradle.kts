import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// local.properties wins locally; CI provides the same values as env vars
fun prop(name: String, default: String = ""): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: default

android {
    namespace = "com.lorem.strawberry"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lorem.strawberry"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.17"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google OAuth Web Client ID (get from Google Cloud Console)
        // This is the WEB client ID, not the Android one
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${prop("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "AUTH_SERVER_URL", "\"${prop("AUTH_SERVER_URL", "https://strawberry-auth.vercel.app")}\"")
        // Comma-separated emails allowed to see the in-app debug log
        buildConfigField("String", "ADMIN_EMAILS", "\"${prop("ADMIN_EMAILS")}\"")
    }

    // Release signing for CI: set SIGNING_KEYSTORE_PATH, SIGNING_KEYSTORE_PASSWORD,
    // SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD (env or local.properties).
    // Without them the release build stays unsigned, same as before.
    val signingKeystorePath = prop("SIGNING_KEYSTORE_PATH")
    if (signingKeystorePath.isNotBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = prop("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = prop("SIGNING_KEY_ALIAS")
                keyPassword = prop("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingKeystorePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val appName = "strawberry"
                val versionName = variant.versionName
                val buildType = variant.buildType.name
                output.outputFileName = "${appName}-${versionName}-${buildType}.apk"
            }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Google Sign-In (Legacy)
    implementation(libs.play.services.auth)

    // Encrypted SharedPreferences
    implementation(libs.security.crypto)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room (chat thread persistence)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coil (image loading)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
