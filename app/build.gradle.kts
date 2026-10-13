plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val backendBaseUrl = providers.environmentVariable("FREELANCE_HUB_API_URL")
    .orElse(providers.gradleProperty("backendBaseUrl"))
    .orElse("")
    .get()

val backendProjectId = providers.environmentVariable("FREELANCE_HUB_PROJECT_ID")
    .orElse(providers.gradleProperty("backendProjectId"))
    .orElse("")
    .get()

val supabaseAnonKey = providers.environmentVariable("FREELANCE_HUB_SUPABASE_ANON_KEY")
    .orElse(providers.gradleProperty("supabaseAnonKey"))
    .orElse("")
    .get()

android {
    namespace = "com.freelancehub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freelancehub.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"${backendBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )

        buildConfigField(
            "String",
            "BACKEND_PROJECT_ID",
            "\"${backendProjectId.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )

        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${supabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("com.google.android.play:app-update:2.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
