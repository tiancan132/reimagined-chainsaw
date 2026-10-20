package com.duyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duyue.reader.data.Prefs
import com.duyue.reader.textaction.TextActionHandler

/**
 * 「问 AI / 查词」目标 App 的配置页。
 * 用户从这里挑出手里常用的 App，最多 4 个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextTargetSheet(
    prefs: Prefs,
    theme: ReaderTheme,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = androidx.compose.ui.platform.LocalContext.current

    // 所有可用的 App
    val available = remember { TextActionHandler.listAvailableApps(context) }

    // 已选中的（从 Prefs 解码）
    var selected by remember {
        mutableStateOf(
            prefs.textTargets.mapNotNull { encoded ->
                val parts = encoded.split("|")
                if (parts.size >= 4) TextActionHandler.TargetApp(parts[1], parts[2], parts[3]) else null
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = theme.bg) {
        Column(
            Modifier
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                "选中文字后的快捷入口",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = theme.text
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "挑几个你常用的 App（最多 4 个）。阅读时长按选中文字，就会看到这些按钮，点一下自动跳过去。",
                fontSize = 12.sp,
                color = theme.sub,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(14.dp))

            // 已选
            Text("已选（${selected.size}/4）", fontSize = 13.sp, color = theme.accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (selected.isEmpty()) {
                Text("还没有选择", fontSize = 12.sp, color = theme.sub)
            } else {
                FlowRowSimple {
                    selected.forEach { app ->
                        AssistChip(
                            onClick = {
                                selected = selected.filterNot { it.packageName == app.packageName }
                                prefs.removeTextTarget(app.packageName)
                            },
                            label = { Text(app.label, fontSize = 12.sp) },
                            trailingIcon = { Text("✕", fontSize = 12.sp, color = theme.sub) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = theme.sub.copy(alpha = 0.2f))
            Spacer(Modifier.height(10.dp))

            if (available.isEmpty()) {
                Text(
                    "你手机里没有找到支持「处理文字」的 App。\n\n常见支持的有：DeepSeek、豆包、各大词典、翻译类 App。装了之后会出现在这里。",
                    fontSize = 13.sp,
                    color = theme.sub,
                    lineHeight = 20.sp
                )
            } else {
                Text(
                    "手机里可用的（${available.size}）",
                    fontSize = 13.sp,
                    color = theme.accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxHeight(0.6f)) {
                    items(available) { app ->
                        val isSelected = selected.any { it.packageName == app.packageName }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 14.sp, color = theme.text)
                                Text(app.packageName, fontSize = 10.sp, color = theme.sub)
                            }
                            if (isSelected) {
                                Text("已添加", fontSize = 12.sp, color = theme.accent)
                            } else {
                                TextButton(
                                    onClick = {
                                        if (selected.size < 4) {
                                            selected = selected + app
                                            prefs.addTextTarget(
                                                "${app.packageName}|${app.label}|${app.packageName}|${app.activityName}"
                                            )
                                        }
                                    },
                                    enabled = selected.size < 4
                                ) {
                                    Text("添加", fontSize = 13.sp)
                                }
                            }
                        }
                        HorizontalDivider(color = theme.sub.copy(alpha = 0.12f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.accent,
                    contentColor = theme.bg
                )
            ) { Text("完成") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 简易流式布局 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content()
    }
}
