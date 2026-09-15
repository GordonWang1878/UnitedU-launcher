import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.unitedu/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        create("release") {
            val ks = file(System.getProperty("user.home") + "/.unitedu/release.jks")
            if (ks.exists() && releaseProps.containsKey("storePassword")) {
                storeFile = ks
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias", "unitedu")
                keyPassword = releaseProps.getProperty("keyPassword")
            } else {
                logger.warn("UnitedU: ~/.unitedu/release.jks not found, signing release with debug keystore")
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
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
            signingConfig = signingConfigs.getByName("release")
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
    // followWallpaperColor:从壁纸位图提取主色(Task F)。从 google maven 镜像解析。
    implementation("androidx.palette:palette-ktx:1.0.0")
    // M6 上传页:应用内 HTTP 服务(BSD-3-Clause)与二维码生成(Apache-2.0),都从 mavenCentral 解析。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
