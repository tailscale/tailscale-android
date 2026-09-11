import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktfmt)
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    val androidApiLevel = providers.gradleProperty("androidApiLevel").get().toInt()

    ndkVersion = "23.1.7779620"
    compileSdk = androidApiLevel

    defaultConfig {
        minSdk = 26
        targetSdk = androidApiLevel

        versionCode = computeVersionCode()
        versionName = getVersionProperty("VERSION_LONG")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "boolean",
            "USE_GOOGLE_DNS_FALLBACK",
            getLocalProperty("tailscale.useGoogleDnsFallback", "true"),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    flavorDimensions += "version"
    namespace = "com.tailscale.ipn"

    buildTypes {
        create("applicationTest") {
            initWith(getByName("debug"))
            manifestPlaceholders["leanbackRequired"] = isTV()

            buildConfigField(
                "String",
                "GITHUB_USERNAME",
                "\"${getLocalProperty("githubUsername", "")}\"",
            )
            buildConfigField(
                "String",
                "GITHUB_PASSWORD",
                "\"${getLocalProperty("githubPassword", "")}\"",
            )
            buildConfigField(
                "String",
                "GITHUB_2FA_SECRET",
                "\"${getLocalProperty("github2FASecret", "")}\"",
            )
        }

        debug {
            manifestPlaceholders["leanbackRequired"] = isTV()
        }

        release {
            manifestPlaceholders["leanbackRequired"] = isTV()
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testBuildType = "applicationTest"
}

dependencies {
    // Android dependencies.
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreKtx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.work.runtimeKtx)

    // Kotlin dependencies.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.ktx)
    runtimeOnly(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    // Compose dependencies.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.toolingPreview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.compose.animation)

    // Navigation dependencies.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.ui)

    // Supporting libraries.
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.vico.compose)
    implementation(libs.vico.composeM3)

    // Tailscale dependencies.
    implementation(files("libs/libtailscale.aar"))

    // Integration tests.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junitKtx)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.junit)

    // Authentication only for tests.
    androidTestImplementation(libs.kotlin.onetimepassword)
    androidTestImplementation(libs.commons.codec)

    // Unit tests.
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}

fun getLocalProperty(key: String, defaultValue: String): String {
    return try {
        val properties = Properties()
        project.file("local.properties").inputStream().use(properties::load)
        properties.getProperty(key) ?: defaultValue
    } catch (_: Throwable) {
        defaultValue
    }
}

fun getVersionProperty(key: String): String {
    val versionProperties = Properties()
    project.file("../tailscale.version").inputStream().use(versionProperties::load)

    return versionProperties
        .getProperty(key)
        .replace(Regex("^\"|\"$"), "")
}

fun computeVersionCode(): Int {
    val baseProperty = project.findProperty("VERSION_CODE_BASE")

    val base =
        baseProperty?.toString()?.toLong() ?: ((System.currentTimeMillis() / 60_000L) * 10L)

    val platform = if (isTV()) 1 else 0
    return (base + platform).toInt()
}

fun isTV(): Boolean {
    return project.findProperty("PLATFORM")?.toString() == "tv"
}
