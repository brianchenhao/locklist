import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.brianchen.locklist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brianchen.locklist"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.1"
        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) load(file.inputStream())
        }
        fun quoted(name: String): String {
            val raw = localProperties.getProperty(name).orEmpty()
            return "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        buildConfigField("String", "SUPABASE_URL", quoted("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted("SUPABASE_ANON_KEY"))
        val githubRepo = localProperties.getProperty("GITHUB_REPO").orEmpty()
            .ifBlank { "brianchenhao/locklist" }
        buildConfigField(
            "String",
            "GITHUB_REPO",
            "\"${githubRepo.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
        buildConfigField("String", "GITHUB_UPDATE_TOKEN", quoted("GITHUB_UPDATE_TOKEN"))
    }

    signingConfigs {
        // Same key for local adb installs and GitHub Release builds, so any APK from either
        // source can replace the one on the phone. CI points LOCKLIST_KEYSTORE at the
        // restored keystore; locally this falls back to the standard debug keystore.
        create("locklist") {
            val keystorePath = System.getenv("LOCKLIST_KEYSTORE")
                ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            storeFile = file(keystorePath)
            storePassword = System.getenv("LOCKLIST_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("LOCKLIST_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("LOCKLIST_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("locklist")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
