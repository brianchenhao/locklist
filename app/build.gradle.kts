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
        versionCode = 4
        versionName = "0.3.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same cert as current adb installs so GitHub APKs can replace the phone build.
            signingConfig = signingConfigs.getByName("debug")
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
