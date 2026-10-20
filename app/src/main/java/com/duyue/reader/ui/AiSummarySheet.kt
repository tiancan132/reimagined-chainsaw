package com.duyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 章节预习面板。
 * 状态机：无 Key → 生成中（流式）→ 已完成 / 出错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSummarySheet(
    theme: ReaderTheme,
    chapterTitle: String,
    hasKey: Boolean,
    isLoading: Boolean,
    text: String,
    error: String?,
    onGenerate: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.bg
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.88f)
                .padding(horizontal = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = theme.accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI 章节预习", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = theme.text)
                    Text(chapterTitle, fontSize = 12.sp, color = theme.sub, maxLines = 1)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭", tint = theme.sub)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = theme.sub.copy(alpha = 0.2f))

            when {
                !hasKey -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔑", fontSize = 44.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("还没有配置 DeepSeek API Key", fontSize = 16.sp, color = theme.text)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "配置后即可用 AI 提前预习每一章",
                            fontSize = 13.sp,
                            color = theme.sub
                        )
                        Spacer(Modifier.height(22.dp))
                        Button(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.accent,
                                contentColor = theme.bg
                            )
                        ) { Text("去配置") }
                    }
                }

                text.isBlank() && isLoading -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = theme.accent)
                        Spacer(Modifier.height(18.dp))
                        Text("AI 正在阅读这一章…", fontSize = 14.sp, color = theme.sub)
                    }
                }

                text.isBlank() && error != null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⚠️", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onGenerate,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.accent,
                                contentColor = theme.bg
                            )
                        ) { Text("重试") }
                    }
                }

                else -> {
                    // 流式输出中或已完成
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 14.dp)
                        ) {
                            // SelectionContainer：长按可选中任意段落复制
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                MarkdownLiteText(text, theme)
                            }
                            if (isLoading) {
                                Spacer(Modifier.height(8.dp))
                                Text("▍", color = theme.accent, fontSize = 16.sp)
                            }
                            Spacer(Modifier.height(40.dp))
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCopyAll,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制全文", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onGenerate,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isLoading) "生成中…" else "重新生成", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        ) { Text("清除", fontSize = 13.sp) }
                    }
                }
            }

            if (hasKey && text.isBlank() && !isLoading && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onGenerate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.accent,
                            contentColor = theme.bg
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("生成本章预习", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/** 极简 Markdown 渲染：只处理 ## 标题、**加粗**、- 列表 */
@Composable
fun MarkdownLiteText(text: String, theme: ReaderTheme) {
    Column {
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        line.removePrefix("## ").trim(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.accent
                    )
                    Spacer(Modifier.height(4.dp))
                }
                line.startsWith("# ") -> {
                    Text(
                        line.removePrefix("# ").trim(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.text
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Row {
                        Text("·  ", color = theme.accent, fontSize = 15.sp)
                        Text(
                            line.removePrefix("- ").removePrefix("* "),
                            fontSize = 15.sp,
                            color = theme.text,
                            lineHeight = 25.sp
                        )
                    }
                }
                else -> Text(
                    line,
                    fontSize = 15.sp,
                    color = theme.text,
                    lineHeight = 25.sp
                )
            }
        }
    }
}
