package com.uniteduone.launcher

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「默认桌面」引导卡。Android 不允许普通应用直接改 HOME 角色,真正的切换只能在系统的
 * 「默认主屏幕应用」页完成(见 switchHome)。这张卡把那个粗糙的系统页包在一次明确点击之后:
 * 先显示当前默认桌面是谁,再给一个「在系统设置中更改」的按钮。风格与齿轮菜单一致。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeSettingsCard(
    currentLabel: String,
    currentPkg: String?,
    onOpenSystem: () -> Unit,
    onDismiss: () -> Unit,
    nonce: Int = 0,
) {
    val ctx = LocalContext.current
    val icon by produceState<Bitmap?>(null, currentPkg) {
        value = currentPkg?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching { drawableToBitmap(ctx.packageManager.getApplicationIcon(pkg)) }.getOrNull()
            }
        }
    }

    val fr = remember { FocusRequester() }
    var landed by remember { mutableStateOf(false) }
    var btnFocused by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF141414))
                .width(360.dp)
                .padding(24.dp),
        ) {
            BasicText(
                text = "默认桌面",
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = Theme.Champagne,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                ),
            )

            Spacer(Modifier.height(18.dp))

            // 当前默认桌面
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A)),
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
                    BasicText(
                        text = "当前",
                        style = TextStyle(fontFamily = Theme.Sans, color = Color(0xFF8A8A8A), fontSize = 11.sp),
                    )
                    BasicText(
                        text = currentLabel,
                        style = TextStyle(
                            fontFamily = Theme.Sans,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF5F5F5),
                            fontSize = 15.sp,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 主按钮:跳系统设置
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (btnFocused) Theme.Champagne.copy(alpha = 0.16f) else Color(0xFF222222))
                    .then(
                        if (btnFocused) Modifier.border(
                            BorderStroke(1.dp, Theme.Champagne.copy(alpha = 0.7f)),
                            RoundedCornerShape(10.dp),
                        ) else Modifier
                    )
                    .focusRequester(fr)
                    .focusProperties {
                        up = FocusRequester.Cancel; down = FocusRequester.Cancel
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }
                    .onFocusChanged { btnFocused = it.isFocused; if (it.isFocused) landed = true }
                    .clickable { onOpenSystem() }
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "在系统设置中更改",
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        fontWeight = FontWeight.Medium,
                        color = if (btnFocused) Theme.Champagne else Color(0xFFCFCFCF),
                        fontSize = 14.sp,
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))

            BasicText(
                text = "Android 要求默认桌面在系统设置里切换。",
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = Color(0xFF7A7A7A),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                ),
            )

            Spacer(Modifier.height(10.dp))

            BasicText(
                text = "按返回键关闭",
                style = TextStyle(fontFamily = Theme.Sans, color = Color(0xFF4A4A4A), fontSize = 10.sp),
            )
        }
    }

    LaunchedEffect(nonce) {
        landed = false
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
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
