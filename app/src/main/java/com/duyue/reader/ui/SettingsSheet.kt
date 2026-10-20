package com.duyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duyue.reader.data.Prefs
import com.duyue.reader.tts.TtsEngineInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    theme: ReaderTheme,
    prefs: Prefs,
    engines: List<TtsEngineInfo>,
    onThemeChange: (ReaderTheme) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
    overlayEnabled: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onEngineChange: (String) -> Unit,
    ttsRate: Float,
    ttsPitch: Float,
    onTtsRate: (Float) -> Unit,
    onTtsPitch: (Float) -> Unit,
    lyricBgAlpha: Int,
    onLyricBgAlpha: (Int) -> Unit,
    onOpenTextTargets: () -> Unit,
    /** 拉取可用模型列表 */
    onFetchModels: (String, (ModelFetchResult) -> Unit) -> Unit = { _, _ -> },
    /** 测试连接：回调 (是否成功, 提示文字) */
    onTestConnection: (String, String, (Boolean, String) -> Unit) -> Unit = { _, _, _ -> }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(prefs.aiModel) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var selectedEngine by remember { mutableStateOf(prefs.ttsEngine) }
    var engineMenu by remember { mutableStateOf(false) }
    val rate = ttsRate
    val pitch = ttsPitch

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.bg
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = theme.text)
            Spacer(Modifier.height(20.dp))

            // ---------------- TTS 引擎 ----------------
            SectionLabel("听书引擎", theme)
            Text(
                "这里列出你手机里所有系统 TTS 引擎（如小爱、讯飞、Google），可自行选择。",
                fontSize = 12.sp,
                color = theme.sub,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(8.dp))

            Box {
                OutlinedButton(
                    onClick = { engineMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = engines.firstOrNull { it.packageName == selectedEngine }?.label
                        ?: engines.firstOrNull()?.label
                        ?: "使用系统默认引擎"
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("▾")
                }
                DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("使用系统默认引擎") },
                        onClick = {
                            engineMenu = false
                            selectedEngine = ""
                            onEngineChange("")
                        }
                    )
                    engines.forEach { e ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(e.label, fontSize = 14.sp)
                                    Text(e.packageName, fontSize = 10.sp, color = theme.sub)
                                }
                            },
                            onClick = {
                                engineMenu = false
                                selectedEngine = e.packageName
                                onEngineChange(e.packageName)
                                onToast("已切换到：${e.label}")
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("语速 ${"%.1f".format(rate)}x", fontSize = 13.sp, color = theme.sub)
            Slider(
                value = rate,
                onValueChange = onTtsRate,
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
            )
            Text("音调 ${"%.1f".format(pitch)}", fontSize = 13.sp, color = theme.sub)
            Slider(
                value = pitch,
                onValueChange = onTtsPitch,
                valueRange = 0.6f..1.6f,
                colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
            )

            // ---------------- 桌面歌词 ----------------
            Spacer(Modifier.height(10.dp))
            SectionLabel("桌面歌词悬浮窗", theme)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在其他应用上显示字幕", fontSize = 14.sp, color = theme.text)
                    Text(
                        "需要「显示在其他应用上层」权限",
                        fontSize = 11.sp,
                        color = theme.sub
                    )
                }
                Switch(
                    checked = overlayEnabled,
                    onCheckedChange = onToggleOverlay,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = theme.bg,
                        checkedTrackColor = theme.accent
                    )
                )
            }

            // ---- 悬浮窗背景透明度 ----
            Spacer(Modifier.height(12.dp))
            Text(
                "歌词背景透明度：${lyricBgAlpha}%${if (lyricBgAlpha == 0) "（全透明）" else ""}",
                fontSize = 13.sp,
                color = theme.sub
            )
            Slider(
                value = lyricBgAlpha.toFloat(),
                onValueChange = { onLyricBgAlpha(it.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = theme.accent,
                    activeTrackColor = theme.accent
                )
            )
            Text(
                "0% = 只有文字；100% = 深色底",
                fontSize = 11.sp,
                color = theme.sub
            )

            // ---------------- 选中文字快捷入口 ----------------
            Spacer(Modifier.height(18.dp))
            SectionLabel("选中文字后的快捷入口", theme)
            Text(
                "长按选中文字后，可直接跳到你常用的 AI 或词典 App",
                fontSize = 11.sp,
                color = theme.sub
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenTextTargets,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("配置常用的 AI / 查词 App（当前 ${prefs.textTargets.size} 个）", fontSize = 13.sp)
            }

            // ---------------- AI ----------------
            Spacer(Modifier.height(18.dp))
            SectionLabel("AI 章节预习（DeepSeek）", theme)
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    prefs.apiKey = it
                },
                label = { Text("API Key（sk-...）") },
                singleLine = true,
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示", fontSize = 12.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "前往 platform.deepseek.com 创建 API Key，按量计费，一章摘要约几分钱。",
                fontSize = 11.sp,
                color = theme.sub,
                lineHeight = 16.sp
            )

            // ---- 模型选择 ----
            Spacer(Modifier.height(14.dp))
            Text("模型", fontSize = 13.sp, color = theme.sub)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        prefs.aiModel = it
                    },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        fetchingModels = true
                        modelMessage = null
                        onFetchModels(apiKey) { result ->
                            fetchingModels = false
                            when (result) {
                                is ModelFetchResult.Success -> {
                                    availableModels = result.models
                                    modelMessage = "已获取 ${result.models.size} 个模型"
                                    // 如果当前模型不在列表里，自动选第一个
                                    if (result.models.isNotEmpty() && model !in result.models) {
                                        model = result.models.first()
                                        prefs.aiModel = model
                                    }
                                }
                                is ModelFetchResult.Failure -> {
                                    // 拉取失败：给一个兜底候选，允许手填
                                    availableModels = listOf("deepseek-chat", "deepseek-reasoner")
                                    modelMessage = result.msg
                                }
                            }
                        }
                    },
                    enabled = !fetchingModels && apiKey.isNotBlank()
                ) {
                    Text(if (fetchingModels) "获取中…" else "获取列表", fontSize = 12.sp)
                }
            }

            // 拉到的模型列表：点一下即可选用
            if (availableModels.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableModels.take(4).forEach { m ->
                        Surface(
                            color = if (m == model) theme.accent.copy(alpha = 0.25f)
                            else theme.sub.copy(alpha = 0.12f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                m,
                                fontSize = 11.sp,
                                color = if (m == model) theme.accent else theme.text,
                                modifier = Modifier
                                    .clickable {
                                        model = m
                                        prefs.aiModel = m
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            modelMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = theme.sub)
            }

            // ---- 测试连接 ----
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        onTestConnection(apiKey, model) { ok, msg ->
                            testing = false
                            testResult = ok to msg
                        }
                    },
                    enabled = !testing && apiKey.isNotBlank()
                ) {
                    Text(if (testing) "测试中…" else "测试连接", fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                testResult?.let { (ok, msg) ->
                    Text(
                        if (ok) "✅ $msg" else "❌ $msg",
                        fontSize = 12.sp,
                        color = if (ok) Color(0xFF4CAF50) else Color(0xFFE05C5C),
                        lineHeight = 16.sp
                    )
                }
            }

            // ---------------- 主题 ----------------
            Spacer(Modifier.height(20.dp))
            SectionLabel("阅读主题", theme)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderTheme.entries.forEach { t ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(t.bg, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t.label,
                            color = t.text,
                            fontSize = 12.sp,
                            fontWeight = if (t == theme) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onThemeChange(t) }
                                .wrapContentSize(Alignment.Center)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("关于", theme)
            Text(
                "读阅 v0.1 · 本地格式支持 TXT / EPUB / PDF\n听书使用手机自带 TTS 引擎，无需联网",
                fontSize = 12.sp,
                color = theme.sub,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, theme: ReaderTheme) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = theme.accent,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** 模型拉取结果 */
sealed interface ModelFetchResult {
    data class Success(val models: List<String>) : ModelFetchResult
    data class Failure(val msg: String) : ModelFetchResult
}
