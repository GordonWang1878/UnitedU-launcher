package com.uniteduone.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 当前默认桌面:显示名 + 包名(包名只用来取图标;解析不到时为 null)。 */
data class CurrentHome(val label: String, val pkg: String?)

/**
 * 按 HOME 会启动谁。设置页的「默认桌面」卡与首次引导第 3 步共用这一处解析(M7 T10 抽出):
 * `resolveActivity(MATCH_DEFAULT_ONLY)` 给的是解析 HOME intent 的结果,与 HOME 键实际拉起谁一致。
 * (2026-09-17 模拟器:`set-home-activity` 之后系统「默认主屏幕应用」页已勾 UnitedU,这里与 HOME 键
 * 却仍指向原厂 Android TV Home——与 T3 记下的「这台 AVD 的 HOME 键不认 set-home-activity」同一现象。)
 * [revision] 当 key:装卸应用(`PACKAGE_*` → `revision++`)之后默认桌面可能换人,跟着重算。
 */
@Composable
fun rememberCurrentHome(revision: Int, refresh: Int = 0): CurrentHome {
    val ctx = LocalContext.current
    val unknown = stringResource(R.string.home_settings_unknown)
    // refresh:设置页外壳「设置默认桌面」页传 focusNonce——从系统「默认主屏幕应用」页改完回来(onResume 必 ++)要重算。
    return remember(revision, refresh, unknown) {
        val pm = ctx.packageManager
        val info = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        CurrentHome(
            label = info?.loadLabel(pm)?.toString() ?: unknown,
            pkg = info?.activityInfo?.packageName,
        )
    }
}

/**
 * 「当前默认桌面」信息行:图标 + 「当前」+ 名字。不可聚焦,纯展示。
 * 设置外壳「设置默认桌面」页(R74,取代原 HomeSettingsCard 浮层)与首次引导第 3 步共用,
 * 两处长得一模一样,不各画一份。图标在 IO 线程取,取不到只留占位底。
 */
/** 左半屏信息块(当前默认桌面、引导的计划列表)的圆角(dp,R135)。 */
internal const val INFO_PANEL_CORNER = 16f

@Composable
fun CurrentHomeRow(
    home: CurrentHome,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val icon by produceState<Bitmap?>(null, home.pkg) {
        value = home.pkg?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching { drawableToBitmap(ctx.packageManager.getApplicationIcon(pkg)) }.getOrNull()
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(INFO_PANEL_CORNER.dp))
            .background(GtvTokens.MenuItemIdle)   // R135:信息块与未聚焦的胶囊同一个底
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Theme.IconPlaceholderBackground),
            contentAlignment = Alignment.Center,
        ) {
            val b = icon
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(text = stringResource(R.string.home_settings_current_label), style = Type.micro)
            BasicText(
                text = home.label,
                maxLines = 1,
                style = Type.label.copy(fontWeight = FontWeight.Medium, color = Ink.Primary),
            )
        }
    }
}

private fun drawableToBitmap(d: Drawable): Bitmap {
    if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
    val w = d.intrinsicWidth.coerceAtLeast(1)
    val h = d.intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    d.setBounds(0, 0, canvas.width, canvas.height)
    d.draw(canvas)
    return bmp
}
