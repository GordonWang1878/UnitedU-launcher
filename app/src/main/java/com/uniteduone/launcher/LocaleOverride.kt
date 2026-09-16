package com.uniteduone.launcher

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换(不依赖 AppCompat 的 `per-app locale` API,那套机制需要 API 33+
 * 且在国行无 GMS 的 TV 系统上行为不可靠)。原理是每次 `Activity.attachBaseContext`
 * 时,用一个带着目标 Locale 的 [Configuration] 派生出一个新 Context 包住 base——
 * 后续 `resources.getString` 等全部经这个派生 Context,读到的就是目标语言的资源。
 *
 * `language` 合法值见 [VALID_LANGUAGES];`"system"` 或任何不认识的值都返回 null,
 * 调用方据此原样使用系统 Context / 系统 Locale。
 */
fun localeFor(language: String): Locale? = when (language) {
    "zh-CN" -> Locale.SIMPLIFIED_CHINESE
    "zh-TW" -> Locale.TRADITIONAL_CHINESE
    "en" -> Locale.ENGLISH
    else -> null
}

/**
 * 用 [language] 对应的 Locale 派生一个新 Context;`"system"` 或未知值(`localeFor` 返回 null)
 * 时原样返回 `this`,即跟随系统语言。
 */
fun Context.withLanguage(language: String): Context {
    val locale = localeFor(language) ?: return this
    val config = Configuration(resources.configuration).apply { setLocales(LocaleList(locale)) }
    return createConfigurationContext(config)
}

/**
 * 当前生效的语言覆盖(`null` = 跟随系统),供 [Clock] 这类不途经 Activity Context
 * 的组合式代码读取。由 `MainActivity.attachBaseContext` 在每次(重)创建 Activity 时写入,
 * 单进程内只有一个前台 Activity 会改它,`@Volatile` 只是防跨线程可见性问题,不是并发写保护。
 */
object AppLocale {
    @Volatile var current: Locale? = null
}
