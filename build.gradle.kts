plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.freelancehub.app"
    compileSdk = 35
    val backendBaseUrl = (System.getenv("FREELANCE_HUB_API_URL") ?: providers.gradleProperty("backendBaseUrl").orNull ?: "").trim()
    val backendProjectId = (System.getenv("FREELANCE_HUB_PROJECT_ID") ?: providers.gradleProperty("backendProjectId").orNull ?: "").trim()
    buildFeatures { buildConfig = true }
    defaultConfig { applicationId = "com.freelancehub.app"; minSdk = 24; targetSdk = 35; versionCode = (System.getenv("FREELANCE_HUB_VERSION_CODE")?.toIntOrNull() ?: 105); versionName = System.getenv("FREELANCE_HUB_VERSION_NAME") ?: "0.105.0"
        buildConfigField("String", "BACKEND_BASE_URL", "\"${backendBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "BACKEND_PROJECT_ID", "\"${backendProjectId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "BACKEND_ENVIRONMENT", "\"production\"") }
    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_FILE")?.trim()
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.trim()
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.trim()
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.trim()
    val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Google Play official in-app update support.
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
