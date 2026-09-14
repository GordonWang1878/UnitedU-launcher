plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uniteduone.launcher"
    compileSdk = 35
    // 必须显式写:AGP 8.7 默认找 34.0.0,而本机手装的是 35.0.0,
    // 缺了它 AGP 会去 dl.google.com 自动下载,而那个域名在这条网络上被掐。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.uniteduone.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // 用 debug keystore 签 release:与 debug 包同一把钥匙,`adb install -r` 能直接覆盖。
        // 换一把新钥匙会因签名不符而必须先卸载,那会连 layout.json、壁纸、自定义图一起删掉。
        create("sideload") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            // 不开 R8 时 material-icons-extended 会把几千个没用到的图标全打进 dex:
            // classes.dex 42.2MB、整包 60.2MB。开了之后 1.44MB / 1.85MB(源码只用 4 个图标)。
            isMinifyEnabled = true
            // **资源裁剪保持关闭**:它只省 128KB,却要靠静态分析判断资源有没有被引用,
            // 对 res/font 这类只在代码里按 R.font.* 引用的东西风险不成比例。
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // 刻意保持最小依赖:不引 tv-material / material3,焦点与动画用标准 Compose 自己控。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    // 行标题图标与齿轮用真的 Material 图标——Projectivy 用的就是这套,
    // 手画的三个形状被复审逐一指出「fill/朝向/笔画都不对」,是最显眼的差异。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
}
