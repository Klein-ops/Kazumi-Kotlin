import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kazumi.port"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kazumi.port"
        minSdk = 24
        targetSdk = 35
        versionCode = 9
        versionName = "2.3.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // 弹弹play 凭据：构建时从 local.properties 读取注入 BuildConfig（local.properties 已被
        // .gitignore 排除，不会进入仓库，推送 GitHub 不含任何密钥）。local.properties 示例：
        //   dandanAppId=xxx
        //   dandanAppSecret=xxx
        // 未配置时为空字符串（不填也能编译）；运行时用户可在「设置→弹幕→弹弹play 凭据」手动填写覆盖。
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) {
                f.inputStream().use { load(it) }
            }
        }
        val dandanAppId = (localProps.getProperty("dandanAppId") ?: "").trim()
        val dandanAppSecret = (localProps.getProperty("dandanAppSecret") ?: "").trim()
        // 历史教训（历史提交 aa83bfb 曾回退 buildConfigField 方案）：CodeAssist 构建引擎对 "$var"
        // 字符串插值会当成字面量、对 \" 转义引号解析会生成未闭合引号，导致 BuildConfig 值错乱/编译失败。
        // 这里避免 $ 插值，并用 \u0022 代替引号字面量，规避解析问题（该写法已在历史提交 159675c 验证可用）。
        buildConfigField("String", "DANDAN_APP_ID", "\u0022" + dandanAppId + "\u0022")
        buildConfigField("String", "DANDAN_APP_SECRET", "\u0022" + dandanAppSecret + "\u0022")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AAPT2 二进制按构建机平台选择：
// - 默认交给 AGP 根据 os.name / os.arch 自动挑选（x86_64 Linux CI、Windows、macOS 都正常）
// - 只有 Linux + arm64（Android/Proot 环境等）才显式指定 linux-aarch64 分类器
// 注意：这里必须写成 "${requested.version}" 让 Kotlin 完成插值；
// 旧实现写成了 ${'$'}{requested.version}，插值后是字面量字符串，等于没生效。
val hostOsName = System.getProperty("os.name").orEmpty().lowercase()
val hostArchName = System.getProperty("os.arch").orEmpty().lowercase()
val isLinuxArm64 = hostOsName.contains("linux") &&
    (hostArchName == "aarch64" || hostArchName == "arm64")

if (isLinuxArm64) {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
                useTarget("com.android.tools.build:aapt2:${requested.version}:linux-aarch64")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Network
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.jsoup)
    implementation(libs.json.path)

    // Storage
    implementation(libs.datastore.preferences)

    // UI
    implementation(libs.coil.compose)

    // Media playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}