import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionProps = Properties().also { props ->
    rootProject.file("version.properties").inputStream().use { stream -> props.load(stream) }
}
val appVersionCode: Int = versionProps.getProperty("versionCode")?.toIntOrNull()
    ?: throw GradleException("version.properties: versionCode missing or invalid")
val appVersionName: String = versionProps.getProperty("versionName")
    ?: throw GradleException("version.properties: versionName missing")

val releaseSigningEnvironmentVariables = listOf(
    "ANDROID_RELEASE_KEYSTORE_PATH",
    "ANDROID_RELEASE_STORE_PASSWORD",
    "ANDROID_RELEASE_KEY_ALIAS",
    "ANDROID_RELEASE_KEY_PASSWORD",
)

val releaseSigningRequested = gradle.startParameter.taskNames.any { taskName ->
    val normalizedTaskName = taskName.substringAfterLast(':')
    normalizedTaskName.equals("assembleRelease", ignoreCase = true) ||
        normalizedTaskName.equals("bundleRelease", ignoreCase = true) ||
        normalizedTaskName.equals("packageRelease", ignoreCase = true) ||
        normalizedTaskName.equals("build", ignoreCase = true) ||
        normalizedTaskName.endsWith("Release", ignoreCase = true)
}

fun requiredReleaseSigningEnv(name: String): String =
    providers.environmentVariable(name).orNull
        ?: throw org.gradle.api.GradleException(
            "Missing release signing environment variable: $name. " +
                "Set ${releaseSigningEnvironmentVariables.joinToString()} before running a release build."
        )

android {
    namespace = "com.hank.flow.open"
    base.archivesName = "OpenFlow"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.hank.flow.open"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti", "-O3", "-fvisibility=hidden")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningRequested) {
                storeFile = file(requiredReleaseSigningEnv("ANDROID_RELEASE_KEYSTORE_PATH"))
                storePassword = requiredReleaseSigningEnv("ANDROID_RELEASE_STORE_PASSWORD")
                keyAlias = requiredReleaseSigningEnv("ANDROID_RELEASE_KEY_ALIAS")
                keyPassword = requiredReleaseSigningEnv("ANDROID_RELEASE_KEY_PASSWORD")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.logan)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
