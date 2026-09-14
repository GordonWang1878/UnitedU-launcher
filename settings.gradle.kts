// 注意:默认的 google() 指向 dl.google.com,这条网络上被掐 SNI(2026-09-10 实测 TLS 失败)。
// dl-ssl.google.com 提供同样的 maven2 内容且可达,所以全部改用它。装 adb 时踩过同一个坑。
val googleMirror = "https://dl-ssl.google.com/dl/android/maven2/"

pluginManagement {
    repositories {
        maven { url = uri("https://dl-ssl.google.com/dl/android/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(googleMirror) }
        mavenCentral()
    }
}
rootProject.name = "TvHome"
include(":app")
