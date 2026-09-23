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

/**
 * 检查更新的通道(spec §7.3),按顺序尝试,逗号分隔。来源:Gradle 属性 `unitedu.updateUrls`
 * (写在 gradle.properties 或命令行 `-Punitedu.updateUrls=…`)。COS 桶地址由 Gordon 自己在
 * gradle.properties 里补上,放在 GitHub 前面;**源码与本文件都不写死任何 COS 地址**。
 * 缺省只有 GitHub Release 这一条。这里不做校验:App 运行时由 UpdateChecker.kt 的
 * `isAllowedUpdateUrl` 把关(只认 https,外加模拟器验证用的 `http://127.0.0.1`),不合规的地址跳过并记日志。
 */
val updateUrls: String = providers.gradleProperty("unitedu.updateUrls").orNull?.takeIf { it.isNotBlank() }
    ?: "https://github.com/GordonWang1878/UnitedU-launcher/releases/latest/download/latest.json"

/**
 * 版本号。发布版固定 2(1.0.0-beta,spec §9);`-PversionCodeOverride=3` 只给模拟器上验证
 * 「发现新版本 → 下载 → 安装」时出一个更高版本号的包用,不进任何配置文件。
 */
val appVersionCode: Int = providers.gradleProperty("versionCodeOverride").orNull?.toInt() ?: 2

/**
 * `-PrequireReleaseKey=true`(`scripts/release.sh` 总是带上):找不到 release 密钥时**构建直接失败**,
 * 不回落 debug keystore。回落本身是给没有密钥的贡献者留的(他们照样能出 release 包自己装);
 * 但发布流程里一旦回落,发出去的包与已装 beta 的签名不同,用户点更新会被系统以 WRONG_SIGNER 拒绝、
 * 只能卸载重装(M7 终审 I4)。release.sh 构建后还会用 apksigner 核对证书摘要,这里是更早的一道闸。
 */
val requireReleaseKey: Boolean = providers.gradleProperty("requireReleaseKey").orNull?.toBoolean() ?: false

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
        versionCode = appVersionCode
        versionName = "1.0.0-beta"
        // 进 Java 字符串字面量:反斜杠与引号先转义(地址里本不该有,防手误把整个构建弄坏)。
        buildConfigField(
            "String",
            "UPDATE_URLS",
            "\"" + updateUrls.replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
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
                if (requireReleaseKey) {
                    throw GradleException(
                        "UnitedU: -PrequireReleaseKey=true, but ~/.unitedu/release.jks or the storePassword in " +
                            "~/.unitedu/release.properties is missing; refusing to sign the release with the debug keystore",
                    )
                }
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
    buildFeatures {
        compose = true
        // 关于页读 BuildConfig.VERSION_NAME / VERSION_CODE / UPDATE_URLS(AGP 8 起默认不生成)。
        buildConfig = true
    }
}

dependencies {
    // tv-material 只用叶子组件与 token(Card / IconButton / MaterialTheme);滚动容器一律不用(铁律 1)。
    // 钉 1.0.0:1.1.0 依赖 Compose 1.10,超出本 BOM。M8 2026-09-17。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // UnitedUDream 直接用 LifecycleRegistry / setViewTreeLifecycleOwner / SavedStateRegistryController,
    // 显式声明、不靠 activity-compose 的传递依赖;版本 = 声明时实际解析到的版本(M5 终审遗留)
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.3")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
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
