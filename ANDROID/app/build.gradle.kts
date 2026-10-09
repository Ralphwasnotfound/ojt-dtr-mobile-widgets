import java.util.Properties
import java.util.Base64
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Android-only configuration; never read or import the web .env.
val nativeConfiguration = Properties().apply {
    val source = rootProject.file("supabase.local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
fun nativeValue(name: String): String = providers.environmentVariable(name).orNull
    ?: nativeConfiguration.getProperty(name, "")
// Reject server-only/malformed key material BEFORE generating BuildConfig or an APK.
val nativeUrl = nativeValue("ANDROID_SUPABASE_URL")
val nativeClientKey = nativeValue("ANDROID_SUPABASE_CLIENT_KEY")
if (nativeClientKey.isNotEmpty()) {
    val clientSafe = try {
        when {
            nativeClientKey.startsWith("sb_publishable_") ->
                nativeClientKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]{16,}"))
            nativeClientKey.split('.').size == 3 -> {
                val payload = String(Base64.getUrlDecoder().decode(nativeClientKey.split('.')[1]), Charsets.UTF_8)
                (JsonSlurper().parseText(payload) as? Map<*, *>)?.get("role") == "anon"
            }
            else -> false
        }
    } catch (_: Exception) { false }
    require(clientSafe && nativeClientKey.none { it.isWhitespace() }) {
        "Android Supabase configuration requires a publishable or legacy anon key. Server-only/invalid material is rejected."
    }
}
fun buildStringLiteral(value: String): String = "\"" + value.replace("\\", "\\\\")
    .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "ph.edu.bsit.tcc.ojtdtr"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ph.edu.bsit.tcc.ojtdtr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", buildStringLiteral(nativeUrl))
        buildConfigField("String", "SUPABASE_CLIENT_KEY", buildStringLiteral(nativeClientKey))
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.android)
    implementation(libs.androidx.browser)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.ktor.mock)

    // Pinned Glance foundation; no application polling worker or background service.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
}
