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
                // GPU 后端（Vulkan/OpenCL）的 cmake 开关与 buildConfigField 改由
                // productFlavors 的 gpu flavor 提供（见下方 productFlavors 块）。
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
            applicationIdSuffix = ".debug"
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

    // 两个维度组合出每包：backend(cpu/gpu) × abi(arm64/x64)。
    // 用「abi 维度 + 每 flavor 单 abiFilters」替代 splits.abi——因为 AGP 不允许
    // splits.abi 与 ndk.abiFilters 同时设置，而 gpu 需要按 ABI 收窄（只出 arm64），
    // 故 ABI 必须做成 flavor 维度。每个变体单 ABI，产物即「每 ABI 一个 APK」。
    flavorDimensions += listOf("backend", "abi")
    productFlavors {
        // --- backend 维度 ---
        // CPU 包：默认后端。
        create("cpu") {
            dimension = "backend"
            buildConfigField("boolean", "OPENFLOW_ENABLE_VULKAN", "false")
            buildConfigField("boolean", "OPENFLOW_ENABLE_OPENCL", "false")
        }
        // GPU 实验包：编入 Vulkan + OpenCL 后端，运行时自动回退 CPU。
        // 仅 arm64（gpu+x64 变体由下方 androidComponents 过滤禁用）。
        create("gpu") {
            dimension = "backend"
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DOPENFLOW_ENABLE_VULKAN=ON", "-DOPENFLOW_ENABLE_OPENCL=ON")
                }
            }
            buildConfigField("boolean", "OPENFLOW_ENABLE_VULKAN", "true")
            buildConfigField("boolean", "OPENFLOW_ENABLE_OPENCL", "true")
        }
        // --- abi 维度 ---（flavor 名须是合法标识符，故用 arm64 / x64，
        // CI 改名时再映射到规范的 arm64-v8a / x86_64）
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 变体裁剪（启用集合）：
//  - release: cpu-arm64, cpu-x64, gpu-arm64        （gpu-x64 始终禁用：x86_64 不出 GPU 包）
//  - debug:   cpu-arm64, cpu-x64                    （gpu-arm64 默认禁用，避免日常
//             assembleDebug 顺带编 gpu 的 181 个 Vulkan shader；加 -PopenflowGpuDebug=true 开启）
androidComponents {
    beforeVariants { variant ->
        val backend = variant.productFlavors.firstOrNull { it.first == "backend" }?.second
        val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second
        val isGpu = backend == "gpu"
        if (isGpu && abi == "x64") {
            variant.enable = false
        }
        if (isGpu && variant.buildType == "debug" && !project.hasProperty("openflowGpuDebug")) {
            variant.enable = false
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
    testImplementation("org.json:json:20240303")
}
