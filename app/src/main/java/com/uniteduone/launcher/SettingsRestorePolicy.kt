package com.uniteduone.launcher

/**
 * `onCreate` 里要不要把设置页从 Bundle 种回来(T8,spec §5)。
 *
 * 只在**我们自己**调用 `recreate()`(语言切换,见 `MainActivity.applyLanguage` 与
 * [MainActivity.selfTriggeredRecreate] 的 KDoc)时才种;任何其它导致这个 Activity 被
 * 销毁重建的原因——包括进程被系统杀掉——都不种,新实例统一落在桌面。
 *
 * **两版实测踩过的坑,记录下来避免以后重踩**:
 * 1. 第一版判据查 `intent.categories` 有没有 `CATEGORY_HOME`,想靠它分辨「这是不是
 *    HOME 键触发的」。被 `recreate()` 的实际行为推翻:`recreate()` 沿用**创建这个 Activity
 *    实例时的旧 intent**,不会因为主动重建而更新;真机上这个 Activity 几乎总是被 HOME
 *    启动的,于是切语言触发的 `recreate()` 里 `intent.categories` 也会是 `{HOME}`,
 *    单独拿它当判据会把「切语言后重开设置页」这个 T8 的核心功能整个关掉(2026-09-17
 *    round 2 用 adb 实测复现:force-stop 后用 HOME intent 启动、进设置页切语言,
 *    `recreate()` 之后新实例的 `intent.categories` 仍是 `{HOME}`,若按「HOME 就不种」
 *    会把这次合法的重开也拦掉——回落到了首页而不是语言行)。
 * 2. 曾设想「进程真的死掉后,新请求的 intent 会被更新,所以死后重建时 intent 才可信」,
 *    想靠这点在「HOME 不种、BACK 种」之间做区分。这个设想同样不成立(round 3 controller
 *    ruling 推翻):一个 `singleTask` activity 死后被重建,新实例的 `intent` 仍然是
 *    `ActivityRecord` 记着的**那个原始启动 intent**(在真机上就是 HOME),不会因为这一次
 *    是被 BACK 带回前台还是被 HOME 重新点亮而有任何不同——`onCreate` 阶段根本拿不到
 *    「这次具体是哪个请求」的信息,HOME 和 BACK 在这里长得一模一样。真正会不同的是
 *    **活着的实例**收到的 `onNewIntent`(那是即时递送的当次请求),但这已经不是
 *    `onCreate`/Bundle restore 要管的事——`onNewIntent` 已经无条件 `leaveSettings()`。
 *
 * 结论:`onCreate` 阶段只有一个可信信号——「这趟重建是不是我们自己主动要的」。是,就种;
 * 不是(不管后面接着来的是 HOME 还是 BACK,`onCreate` 都分不出来,统一按「不种」处理,
 * 落在桌面是更安全的默认值),就不种。
 *
 * **[onboardingOpen] 为真时一律不种**(M7 终审 I2)。引导开没开是 `onCreate` 按 settings.json
 * 重新判的,与 Bundle 无关;而 `endOnboarding` 在写盘失败时也照样收起引导(盘上仍是
 * `onboardingDone = false`)。之后若在设置页切语言,自己触发的 `recreate()` 会让新实例
 * **同时**判出「要引导」和「设置页开着」——两层整屏浮层各带一套焦点账本,互相抢焦点。
 * 引导画在最上层、而且 spec §8 要求它期间别的浮层都打不开,所以让设置页这一侧放弃。
 */
internal fun shouldRestoreSettingsFromBundle(
    bundleSaysOpen: Boolean,
    selfTriggeredRecreate: Boolean,
    onboardingOpen: Boolean,
): Boolean = bundleSaysOpen && selfTriggeredRecreate && !onboardingOpen
