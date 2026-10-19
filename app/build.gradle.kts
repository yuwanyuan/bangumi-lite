import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bangumi.ywylite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bangumi.ywylite"
        minSdk = 26
        targetSdk = 36
        // CI 约定：GITHUB_RUN_NUMBER 保证 versionCode 全局单调递增（回跳会被 Android 拒装）；
        // tag 构建（v*）额外注入 APP_VERSION_NAME 作为正式版本号，master 构建回落为
        // <base>-dev.N，N 由 workflow 按「距上一个 v* 标签的提交数」注入，故每次发完
        // 正式版后测试版序号自动从 1 重新开始。
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val tagVersion = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() }?.removePrefix("v")
        val devBuild = System.getenv("APP_DEV_BUILD")?.toIntOrNull() ?: 1
        versionCode = runNumber ?: 2
        versionName = tagVersion ?: "1.2.5-dev.$devBuild"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("io.ktor:ktor-client-okhttp:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    implementation("io.coil-kt:coil-compose:2.5.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Haze：毛玻璃效果（hazeSource 标记背景层，hazeChild 在前景控件采样模糊）
    // 用 1.5.3：对齐本项目 Compose 1.7.x / AGP 8.7.3；更高版本要求 compileSdk 37 或 AGP 8.9+
    implementation("dev.chrisbanes.haze:haze:1.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
