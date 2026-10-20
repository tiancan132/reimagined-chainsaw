package com.duyue.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import com.duyue.reader.model.Chapter
import com.duyue.reader.tts.SpeechPosition

/**
 * 阅读页（v0.6 连续滚动模式）。
 *
 * 用户确认的设计：
 *   1. 正文**上下自由滚动**，不做左右翻页（零手势冲突）
 *   2. **点击句子 = 从这句开始朗读**
 *   3. 听书时**最小滚动**：当前句还在屏幕内就不动，快出屏才滚
 *   4. 读完本章自动进入下一章（由 MainActivity 驱动）
 *
 * 长按进入选择模式后可拖选文字，走系统菜单（复制 / 问 DeepSeek / 查词）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    bookTitle: String,
    chapter: Chapter,
    theme: ReaderTheme,
    fontSize: Float,
    lineHeight: Float,
    position: SpeechPosition,
    isSpeaking: Boolean,
    isPaused: Boolean,
    ttsReady: Boolean,
    /** 上次读到的片段下标：进章节时直接滚到对应段落，-1 表示从头开始 */
    initialClipIndex: Int = -1,
    chapterIndex: Int = 0,
    chapterCount: Int = 1,
    hasNextChapter: Boolean = false,
    onBack: () -> Unit,
    onChapterList: () -> Unit,
    onFontSettings: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onSentenceClick: (Int) -> Unit,
    onOpenAiSummary: () -> Unit,
    overlayVisible: Boolean = false,
    onToggleOverlay: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    /** 选中文字后的快捷操作：action 为 "copy" / "search" / "target:<key>" */
    onSelectionAction: (String, String) -> Unit = { _, _ -> },
    /** 设置里配置的快捷目标 App */
    selectionTargets: List<Pair<String, String>> = emptyList(),
    onSelectionModeChanged: (Boolean) -> Unit = {}
) {
    var showTopBar by remember { mutableStateOf(true) }
    // 长按选字状态：选中的片段全局下标范围 [start, end]（含两端），-1 表示未选中
    var selStartIdx by remember { mutableStateOf(-1) }
    var selEndIdx by remember { mutableStateOf(-1) }
    // 「用户是否在播放中」——初始值从 TTS 真实状态读出来。
    // 这样返回书架再进书（页面重建）时，若 TTS 还在后台读，点文字仍能跳转。
    var userPlaying by remember { mutableStateOf(isSpeaking && !isPaused) }
    val listState = rememberLazyListState()

    // 当前选中的文字（拼接选中片段），供底部快捷条显示
    val selectedText = remember(chapter.clips, selStartIdx, selEndIdx) {
        if (selStartIdx < 0 || selEndIdx < selStartIdx) ""
        else {
            val lo = selStartIdx.coerceIn(0, chapter.clips.size - 1)
            val hi = selEndIdx.coerceIn(lo, chapter.clips.size - 1)
            (lo..hi).joinToString("") { chapter.clips[it].text }
        }
    }

    /**
     * 第 pi 段对应的第一个片段下标。
     *
     * 直接用 Chapter.firstClipOfParagraph —— 片段自带 paragraphIndex，映射精确，
     * 不再靠逐段累加切分结果去猜（那正是之前「一点击就跳到末尾」的根因）。
     */
    fun firstClipOf(pi: Int): Int = chapter.firstClipOfParagraph(pi)

    /**
     * 当前朗读片段属于哪一段（直接读片段自带字段，O(1)）。
     */
    val currentParagraph: Int =
        chapter.clips.getOrNull(position.sentenceIndex)?.paragraphIndex ?: -1

    // 切换章节：清空选中状态，滚到上次读到的位置（没有则回顶部）
    LaunchedEffect(chapter.index) {
        selStartIdx = -1
        selEndIdx = -1
        // 依据 initialClipIndex 找到它所在的段落，直接滚过去
        val target = if (initialClipIndex >= 0) {
            val paraIdx = chapter.clips.getOrNull(initialClipIndex)?.paragraphIndex ?: 0
            // item 0 是章节标题，段落 p 对应 item index = p + 1
            (paraIdx + 1).coerceIn(0, maxOf(0, chapter.paragraphs.size))
        } else 0
        runCatching { listState.scrollToItem(target) }
    }

    /**
     * 听书跟随（最小滚动）。
     *
     * ⚠️ 关键：列表的 item 是**段落**，不是片段。
     * 之前误把片段序号 position.sentenceIndex 当成 item 下标（idx + 1），
     * 而一本书可能有几千个片段、列表只有几十段，二者永远对不上，
     * 于是每次都判定「不在可视区」并盲目滚动 —— 这就是「一点播放就跳下去」的根因。
     *
     * 正确做法：用当前片段所属的**段落号** currentParagraph 去找 item。
     */
    LaunchedEffect(currentParagraph, isSpeaking) {
        if (!isSpeaking) return@LaunchedEffect
        val pIdx = currentParagraph
        if (pIdx < 0 || pIdx >= chapter.paragraphs.size) return@LaunchedEffect

        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect

        // LazyColumn 第 0 项是章节标题，段落 p 对应 item index = p + 1
        val targetItem = pIdx + 1
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset
        val height = (bottom - top).coerceAtLeast(1)

        val itemInfo = visible.firstOrNull { it.index == targetItem }
        val outOfView = itemInfo == null ||
            itemInfo.offset < top ||
            itemInfo.offset + itemInfo.size > bottom

        if (outOfView) {
            runCatching {
                listState.animateScrollToItem(targetItem, scrollOffset = -(height / 3))
            }
        }
    }

    val totalSentences = chapter.sentences.size.coerceAtLeast(1)
    val currentSentence = if (isSpeaking || isPaused) position.sentenceIndex else 0
    val chapterProgress = ((currentSentence + 1).toFloat() / totalSentences).coerceIn(0f, 1f)
    val bookProgress = if (chapterCount <= 0) 0f
    else ((chapterIndex + chapterProgress) / chapterCount).coerceIn(0f, 1f)

    Scaffold(
        containerColor = theme.bg,
        topBar = {
            if (showTopBar) {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(bookTitle, fontSize = 12.sp, color = theme.sub, maxLines = 1)
                                Text(
                                    chapter.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.text,
                                    maxLines = 1
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = theme.text)
                            }
                        },
                        actions = {
                            IconButton(onClick = onChapterList) {
                                Icon(Icons.Default.List, "目录", tint = theme.text)
                            }
                            IconButton(onClick = onFontSettings) {
                                Icon(Icons.Default.Edit, "字号", tint = theme.text)
                            }
                            IconButton(onClick = onOpenAiSummary) {
                                Icon(Icons.Default.Star, "AI 预习", tint = theme.accent)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.bg)
                    )
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LinearProgressIndicator(
                            progress = { chapterProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = theme.accent,
                            trackColor = theme.sub.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(4.dp))
                        // 一行搞定：上一章 + 章节信息 + 下一章
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "◀ 上一章",
                                fontSize = 11.sp,
                                color = if (chapterIndex > 0) theme.accent
                                        else theme.sub.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .clickable(enabled = chapterIndex > 0) { onPrevChapter() }
                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "第 ${chapterIndex + 1}/${chapterCount} 章 · 本章 ${(chapterProgress * 100).toInt()}% · 全书 ${(bookProgress * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = theme.sub
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "下一章 ▶",
                                fontSize = 11.sp,
                                color = if (chapterIndex < chapterCount - 1) theme.accent
                                        else theme.sub.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .clickable(enabled = chapterIndex < chapterCount - 1) { onNextChapter() }
                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                // 长按选中文字后：底部显示预览 + 操作按钮
                if (selectedText.isNotBlank()) {
                    SelectionActionBar(
                        selectedText = selectedText,
                        theme = theme,
                        onAction = { action ->
                            if (action == "read_from_here") {
                                // 从选中的第一句开始读，并进入播放状态
                                val start = selStartIdx
                                selStartIdx = -1
                                selEndIdx = -1
                                userPlaying = true
                                if (start >= 0) onSentenceClick(start)
                            } else {
                                onSelectionAction(action, selectedText)
                            }
                        },
                        onDismiss = {
                            selStartIdx = -1
                            selEndIdx = -1
                        },
                        targets = selectionTargets,
                        showPlayFromHere = selStartIdx >= 0
                    )
                }
                PlayerBar(
                    theme = theme,
                    isSpeaking = isSpeaking,
                    isPaused = isPaused,
                    ttsReady = ttsReady,
                    overlayVisible = overlayVisible,
                    hasNextChapter = hasNextChapter,
                    onPlayPause = {
                        if (userPlaying) {
                            // 正在播放 → 这次点击是"暂停"
                            userPlaying = false
                            onPlayPause()
                        } else if (isPaused) {
                            // 暂停中 → 继续播放
                            userPlaying = true
                            onPlayPause()
                        } else {
                            // 停止/从未播放 → 从当前屏幕最上面那段的第一句开始读
                            val firstVisible = listState.firstVisibleItemIndex
                            // item 0 是章节标题，段落 p 对应 item index = p + 1
                            val paraIdx = (firstVisible - 1).coerceAtLeast(0)
                                .coerceAtMost(maxOf(0, chapter.paragraphs.size - 1))
                            val startClip = if (chapter.paragraphs.isEmpty()) -1
                                            else chapter.firstClipOfParagraph(paraIdx)
                            userPlaying = true
                            if (startClip >= 0 && chapter.clips.isNotEmpty()) {
                                onSentenceClick(startClip)
                            } else {
                                onPlayPause()
                            }
                        }
                    },
                    onStop = {
                        userPlaying = false
                        onStop()
                    },
                    onPrev = onPrevSentence,
                    onNext = onNextSentence,
                    onNextChapter = onNextChapter,
                    onToggleOverlay = onToggleOverlay
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
            ) {
                item(key = "title") {
                    Text(
                        chapter.title,
                        fontSize = (fontSize + 5).sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.text,
                            lineHeight = ((fontSize + 5) * 1.4f).sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                    itemsIndexed(
                        items = chapter.paragraphs,
                        key = { i, _ -> "p_$i" }
                    ) { pIdx, paragraph ->
                        // 当前朗读片段是否落在这一段里
                        val isActiveParagraph = currentParagraph == pIdx
                        // 本段包含哪些片段（用于段内命中检测）
                        val clipsOfPara = remember(chapter.index, pIdx) {
                            chapter.clips.filter { it.paragraphIndex == pIdx }.map { it.text }
                        }
                        val firstGlobal = remember(chapter.index, pIdx) { firstClipOf(pIdx) }

                        ParagraphBody(
                            paragraph = paragraph,
                            clipsOfParagraph = clipsOfPara,
                            firstClipGlobalIdx = firstGlobal,
                            isActive = isActiveParagraph,
                            activeClipText = if (isActiveParagraph) position.sentenceText else null,
                            currentClipGlobalIdx = position.sentenceIndex,
                            selStart = selStartIdx,
                            selEnd = selEndIdx,
                            theme = theme,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            onClipClick = { globalIdx ->
                                // 只有用户处于「播放中」（未暂停）时，点哪句才读哪句。
                                // 暂停 / 停止 / 从未播放 → 点文字无任何反应。
                                if (userPlaying) {
                                    onSentenceClick(globalIdx)
                                }
                            },
                            onLongPressStart = { globalIdx ->
                                // 长按：从该句开始选中
                                selStartIdx = globalIdx
                                selEndIdx = globalIdx
                            },
                            onDragSelect = { globalIdx ->
                                // 拖动：扩大/缩小选中范围
                                if (selStartIdx >= 0) {
                                    selStartIdx = minOf(selStartIdx, globalIdx)
                                    selEndIdx = maxOf(selEndIdx, globalIdx)
                                }
                            },
                            onBlankTap = {
                                // 点空白：取消选择高亮
                                selStartIdx = -1
                                selEndIdx = -1
                            }
                        )
                    }

                    item(key = "footer") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "本章完",
                                fontSize = 13.sp,
                                color = theme.sub
                            )
                            Spacer(Modifier.height(16.dp))

                            if (hasNextChapter) {
                                Button(
                                    onClick = onNextChapter,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = theme.accent,
                                        contentColor = theme.bg
                                    ),
                                    contentPadding = PaddingValues(
                                        horizontal = 32.dp,
                                        vertical = 12.dp
                                    )
                                ) {
                                    Text(
                                        "下一章",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "点击后跳转并继续朗读",
                                    fontSize = 11.sp,
                                    color = theme.sub
                                )
                            } else {
                                Text(
                                    "已是本书最后一章",
                                    fontSize = 13.sp,
                                    color = theme.sub
                                )
                            }
                        }
                    }
                }
        }
    }
}

/**
 * 一段正文。
 *
 * 交互：
 *   · 单击某句 → 从该句朗读
 *   · 长按某句 → 选中该句（高亮）
 *   · 长按后拖动 → 扩大/缩小选中范围（相邻句）
 *   · 点空白 → 退出选择
 *
 * 选中的句子用背景色高亮，一眼能看出选中了哪些内容。
 */
@Composable
private fun ParagraphBody(
    paragraph: String,
    clipsOfParagraph: List<String>,
    firstClipGlobalIdx: Int,
    isActive: Boolean,
    activeClipText: String?,
    currentClipGlobalIdx: Int,
    selStart: Int,
    selEnd: Int,
    theme: ReaderTheme,
    fontSize: Float,
    lineHeight: Float,
    onClipClick: (Int) -> Unit,
    onLongPressStart: (Int) -> Unit,
    onDragSelect: (Int) -> Unit,
    onBlankTap: () -> Unit = {}
) {
    val indent = "　　"

    // 建立「段落内字符区间 → 全局片段下标」的映射
    val ranges = remember(paragraph, clipsOfParagraph) {
        val list = ArrayList<Triple<Int, Int, Int>>()
        clipsOfParagraph.forEachIndexed { i, clipText ->
            val start = paragraph.indexOf(clipText, if (list.isEmpty()) 0 else list.last().second - indent.length)
            if (start >= 0) {
                list.add(Triple(start + indent.length, start + indent.length + clipText.length, firstClipGlobalIdx + i))
            }
        }
        list
    }

    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    // 渲染：选中范围高亮 + 朗读句高亮
    val text = remember(paragraph, isActive, activeClipText, theme, selStart, selEnd, ranges, firstClipGlobalIdx, currentClipGlobalIdx) {
        buildAnnotatedString {
            append(indent)
            // 逐片段渲染，选中片段加背景高亮
            clipsOfParagraph.forEachIndexed { i, clipText ->
                val globalIdx = firstClipGlobalIdx + i
                val isSelected = selStart >= 0 && globalIdx in selStart..selEnd
                val isCurrent = isActive && globalIdx == currentClipGlobalIdx

                val style = when {
                    isSelected -> SpanStyle(
                        color = theme.text,
                        background = theme.accent.copy(alpha = 0.3f),
                        fontWeight = FontWeight.Bold
                    )
                    isCurrent -> SpanStyle(color = theme.accent, fontWeight = FontWeight.Bold)
                    else -> SpanStyle(color = theme.text)
                }
                withStyle(style) { append(clipText) }
            }
        }
    }

    Text(
        text = text,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeight).sp,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(ranges, firstClipGlobalIdx) {
                // 单一手势状态机：一个检测器处理全部情况，避免多个检测器互相抢事件。
                //
                //   按下
                //    ├─ 500ms 内抬起（没移动）  → 单击：朗读该句
                //    ├─ 按住超 500ms           → 长按：选中该句
                //    │      └─ 继续移动         → 拖动：扩大/缩小选区
                //    └─ 快速移动                → 交给列表滚动（不消费事件）
                awaitEachGesture {
                    // requireUnconsumed = true：只有列表没有消费触摸（即不是滚动）时，
                    // 才轮到我们处理点击/长按/拖选。这样滚动完全交给列表，丝滑不卡。
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val layout = layoutResult
                    val startChar = layout?.getOffsetForPosition(down.position) ?: -1
                    val startHit = ranges.firstOrNull { (s, e, _) -> startChar in s until e }

                    var longPressed = false
                    var moved = false
                    var consumed = false

                    // 记录按下时间，用于判断长按（本地毫秒计时）
                    val downTime = System.currentTimeMillis()

                    while (true) {
                        // 等事件，最多等到"长按超时"那一刻
                        val remain = if (!longPressed && !moved)
                            500L - (System.currentTimeMillis() - downTime)
                        else -1L

                        val event = if (remain > 0) {
                            withTimeoutOrNull(remain) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            // 超时且没动、没抬起 → 触发长按
                            if (!longPressed && !moved && startHit != null) {
                                longPressed = true
                                onLongPressStart(startHit.third)
                            }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            // 手指抬起
                            change.consume()
                            if (!longPressed && !moved) {
                                // 单击
                                if (startHit != null) onClipClick(startHit.third)
                                else onBlankTap()
                            }
                            break
                        }

                        // 手指仍按着：判断是否移动
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!moved && (kotlin.math.abs(dx) > 12f || kotlin.math.abs(dy) > 12f)) {
                            moved = true
                            // 还没触发长按就移动了 → 这是滚动，立刻退出，把事件还给列表
                            if (!longPressed) {
                                break
                            }
                        }

                        if (longPressed && moved) {
                            // 长按后拖动 → 扩大/缩小选区
                            val c = layout?.getOffsetForPosition(change.position) ?: -1
                            val hit = ranges.firstOrNull { (s, e, _) -> c in s until e }
                            if (hit != null) {
                                onDragSelect(hit.third)
                                change.consume()
                                consumed = true
                            }
                        }
                        // 未长按就移动 → 不消费，交给列表滚动
                    }
                    // consumed 仅用于表明拖动期间已消费事件，避免误触外层滚动
                    if (consumed) { /* no-op */ }
                }
            }
    )
}

/** 单句文字（选择模式使用） */
@Composable
private fun SentenceBody(
    sentence: String,
    globalIdx: Int,
    position: SpeechPosition,
    isSpeaking: Boolean,
    theme: ReaderTheme,
    fontSize: Float,
    lineHeight: Float
) {
    val isCurrent = isSpeaking && globalIdx == position.sentenceIndex

    val text = if (isCurrent) {
        buildAnnotatedString {
            if (position.charEnd > position.charStart && position.charStart < sentence.length) {
                val s = position.charStart.coerceIn(0, sentence.length)
                val e = position.charEnd.coerceIn(s, sentence.length)
                withStyle(SpanStyle(color = theme.accent, fontWeight = FontWeight.Bold)) {
                    append(sentence.substring(0, e))
                }
                append(sentence.substring(e))
            } else {
                withStyle(SpanStyle(color = theme.accent, fontWeight = FontWeight.Bold)) {
                    append(sentence)
                }
            }
        }
    } else {
        buildAnnotatedString { append(sentence) }
    }

    Text(
        text = text,
        color = theme.text,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeight).sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}

/**
 * 长按选中文字后，底部弹出的快捷条。
 *
 * 只做一件最有用的事：把选中的文字送去查询或复制。
 * 不显示「退出」按钮 —— 点空白处或点「收起」即可。
 */
@Composable
private fun SelectionActionBar(
    selectedText: String,
    theme: ReaderTheme,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 用户在设置里配置的目标 App（key = "pkg|activity"，label = 显示名） */
    targets: List<Pair<String, String>> = emptyList(),
    /** 是否显示「从这里读」按钮（有明确的起始片段时才显示） */
    showPlayFromHere: Boolean = false
) {
    Surface(
        color = if (theme == ReaderTheme.NIGHT) Color(0xFF23252A) else Color(0xFF2B2B2B),
        tonalElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selectedText,
                    color = Color(0xFFEDEDED),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "收起",
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(start = 10.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 只显示用户在设置里挑的前两个 App
                targets.take(2).forEach { (key, label) ->
                    SelectionChip(label, theme) { onAction("target:$key") }
                }
                // 通用操作固定保留
                if (showPlayFromHere) {
                    SelectionChip("▶ 从这里读", theme) { onAction("read_from_here") }
                }
                SelectionChip("复制", theme) { onAction("copy") }
                SelectionChip("搜索", theme) { onAction("search") }
            }
            if (targets.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "去「设置 → 选中文字快捷入口」添加常用 App",
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SelectionChip(label: String, theme: ReaderTheme, onClick: () -> Unit) {
    Surface(
        color = theme.accent,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Text(
            label,
            color = theme.bg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun PlayerBar(
    theme: ReaderTheme,
    isSpeaking: Boolean,
    isPaused: Boolean,
    ttsReady: Boolean,
    overlayVisible: Boolean,
    hasNextChapter: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onNextChapter: () -> Unit,
    onToggleOverlay: () -> Unit
) {
    Surface(color = theme.bg, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    "上一句",
                    tint = theme.text,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(
                onClick = onPlayPause,
                enabled = ttsReady,
                modifier = Modifier.size(50.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = theme.accent,
                    contentColor = theme.bg
                )
            ) {
                when {
                    !ttsReady -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = theme.bg
                    )
                    isSpeaking -> Icon(Icons.Default.Pause, "暂停", modifier = Modifier.size(24.dp))
                    else -> Icon(Icons.Default.PlayArrow, "播放", modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    "下一句",
                    tint = theme.text,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onToggleOverlay, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (overlayVisible) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                    if (overlayVisible) "隐藏桌面字幕" else "显示桌面字幕",
                    tint = if (overlayVisible) theme.accent else theme.sub,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (isSpeaking || isPaused) {
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Stop,
                        "停止",
                        tint = theme.text,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * 目录抽屉（v0.12 分卷折叠）。
 *
 * 交互：
 *   · 点卷名行     → 跳到该卷第一章
 *   · 点左侧箭头   → 收起 / 展开该卷
 *   · 点章节       → 跳到该章
 *   · 顶部按钮     → 全部展开 / 全部收起
 *   · 默认全部展开
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    chapters: List<Chapter>,
    current: Int,
    theme: ReaderTheme,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 1) 识别分卷并把章节分组
    val groups = remember(chapters) { groupByVolume(chapters) }

    // 2) 各卷的展开状态，默认全部展开
    //    用 SnapshotStateMap 记录，索引为卷序号，值为是否展开。
    //    （之前用的 SnapshotStateList + 自定义 setAt 没有正确触发重组，导致点箭头没反应）
    val expandedMap = remember(groups) {
        mutableStateMapOf<Int, Boolean>().apply {
            groups.indices.forEach { put(it, true) }
        }
    }

    // 折叠后可见的条目列表（卷头 + 展开卷里的章节）
    val visibleItems = remember(groups) {
        mutableStateListOf<CatalogItem>().also { list ->
            groups.forEachIndexed { gi, g ->
                list.add(CatalogItem.VolumeHeader(gi, g.title, g.firstChapterIndex, g.chapters.size))
                g.chapters.forEach { (ci, title) ->
                    list.add(CatalogItem.ChapterItem(ci, title, gi))
                }
            }
        }
    }

    // 根据展开状态过滤出真正要显示的行
    val shownItems = visibleItems.filter { item ->
        when (item) {
            is CatalogItem.VolumeHeader -> true
            is CatalogItem.ChapterItem -> expandedMap[item.groupIndex] ?: true
        }
    }

    // 打开目录时，确保当前章所在的卷是展开的（否则定位不到它）
    LaunchedEffect(current) {
        val g = groups.indexOfFirst { grp ->
            current in grp.firstChapterIndex until (grp.firstChapterIndex + grp.chapters.size)
        }
        if (g >= 0) expandedMap[g] = true
    }

    val listState = rememberLazyListState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.bg
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("目录", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = theme.text)
                    Text(
                        "共 ${chapters.size} 章 · ${groups.size} 卷 · 当前第 ${current + 1} 章",
                        fontSize = 12.sp,
                        color = theme.sub
                    )
                }
                val allExpanded = groups.indices.all { expandedMap[it] ?: true }
                TextButton(
                    onClick = {
                        val target = !allExpanded
                        groups.indices.forEach { expandedMap[it] = target }
                    }
                ) {
                    Text(
                        if (allExpanded) "全部收起" else "全部展开",
                        fontSize = 13.sp,
                        color = theme.accent
                    )
                }
            }
            HorizontalDivider(color = theme.sub.copy(alpha = 0.15f))

            /**
             * 打开目录时自动定位到当前章节：
             *   1) 先确保当前章所在的卷是展开的（否则列表里根本找不到它）
             *   2) 再算出当前章在「卷头 + 章节」混合列表里的位置并滚过去
             */
            LaunchedEffect(shownItems) {
                val targetIndex = shownItems.indexOfFirst { item ->
                    item is CatalogItem.ChapterItem && item.chapterIndex == current
                }
                if (targetIndex >= 0) {
                    // 让当前章大致出现在列表上部（留两行上下文）
                    runCatching {
                        listState.scrollToItem((targetIndex - 2).coerceAtLeast(0))
                    }
                }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.85f)) {
                itemsIndexed(shownItems) { _, item ->
                    when (item) {
                        is CatalogItem.VolumeHeader -> {
                            val isOpen = expandedMap[item.groupIndex] ?: true
                            // 当前章是否在本卷内
                            val containsCurrent =
                                current in item.firstChapterIndex until (item.firstChapterIndex + item.count)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(theme.accent.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 箭头：只负责收起 / 展开
                                IconButton(
                                    onClick = {
                                        expandedMap[item.groupIndex] = !isOpen
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Text(
                                        if (isOpen) "▼" else "▶",
                                        fontSize = 12.sp,
                                        color = theme.accent
                                    )
                                }
                                // 卷名文字：点击跳到本卷第一章
                                Text(
                                    item.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (containsCurrent) theme.accent else theme.text,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSelect(item.firstChapterIndex) }
                                        .padding(vertical = 14.dp)
                                )
                                Text(
                                    "${item.count} 章",
                                    fontSize = 11.sp,
                                    color = theme.sub,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            HorizontalDivider(color = theme.sub.copy(alpha = 0.18f))
                        }

                        is CatalogItem.ChapterItem -> {
                            val isCurrent = item.chapterIndex == current
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isCurrent) theme.accent.copy(alpha = 0.12f) else theme.bg
                                    )
                                    .clickable { onSelect(item.chapterIndex) }
                                    .padding(start = 48.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    item.title,
                                    color = if (isCurrent) theme.accent else theme.text,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isCurrent) {
                                    Text("正在读", fontSize = 11.sp, color = theme.accent)
                                }
                            }
                            HorizontalDivider(color = theme.sub.copy(alpha = 0.10f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 目录分卷

/** 目录条目：卷头 或 章节 */
private sealed interface CatalogItem {
    data class VolumeHeader(
        val groupIndex: Int,
        val title: String,
        val firstChapterIndex: Int,
        val count: Int
    ) : CatalogItem

    data class ChapterItem(
        val chapterIndex: Int,
        val title: String,
        val groupIndex: Int
    ) : CatalogItem
}

/** 一卷的内容 */
private data class VolumeGroup(
    val title: String,
    /** (章节下标, 标题) */
    val chapters: List<Pair<Int, String>>,
    val firstChapterIndex: Int
)

/**
 * 按「卷 / 篇 / 部 / 集」把章节分组。
 * 规则按市面常规：标题含 卷篇部集 视为分卷头；含 章节回 视为普通章节。
 * 第一个分卷之前的内容归入「开头」。
 */
private fun groupByVolume(chapters: List<Chapter>): List<VolumeGroup> {
    if (chapters.isEmpty()) return emptyList()

    val volumeRegex = Regex("第?[0-9零一二三四五六七八九十百千万两]+[卷篇部集]|[卷篇部集][0-9一二三四五六七八九十百千万两]+")
    // 只有标题**以**分卷标记开头才算卷头，避免章节名里出现「卷」字被误判
    val isVolumeHead = { title: String ->
        val t = title.trim()
        volumeRegex.find(t)?.let { it.range.first <= 2 } ?: false
    }

    val groups = ArrayList<VolumeGroup>()
    var currentHead: String? = null
    var currentList = ArrayList<Pair<Int, String>>()

    fun flush() {
        if (currentHead == null && currentList.isEmpty()) return
        if (currentHead == null) {
            if (currentList.isNotEmpty()) {
                groups.add(VolumeGroup("开头", currentList.toList(), currentList.first().first))
                currentList = ArrayList()
            }
        } else {
            groups.add(VolumeGroup(currentHead!!, currentList.toList(), currentList.firstOrNull()?.first ?: 0))
            currentHead = null
            currentList = ArrayList()
        }
    }

    chapters.forEachIndexed { idx, ch ->
        if (isVolumeHead(ch.title)) {
            flush()
            currentHead = ch.title
            currentList = ArrayList()
        } else {
            currentList.add(idx to ch.title)
        }
    }
    flush()

    // 如果一个卷都没识别出来，就退化成一整组
    if (groups.isEmpty()) {
        return listOf(VolumeGroup("全部章节", chapters.mapIndexed { i, c -> i to c.title }, 0))
    }
    return groups
}

/** 字号 / 主题设置面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSheet(
    theme: ReaderTheme,
    fontSize: Float,
    lineHeight: Float,
    onTheme: (ReaderTheme) -> Unit,
    onFontSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = theme.bg) {
        Column(
            Modifier
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp)
        ) {
            SettingTitle("主题", theme)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderTheme.entries.forEach { t ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .background(
                                t.bg,
                                androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                            )
                            .clickable { onTheme(t) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t.label,
                            color = t.text,
                            fontSize = 13.sp,
                            fontWeight = if (t == theme) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            SettingTitle("字号 ${fontSize.toInt()}", theme)
            Slider(
                value = fontSize,
                onValueChange = onFontSize,
                valueRange = 13f..30f,
                steps = 16,
                colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
            )
            Spacer(Modifier.height(14.dp))
            SettingTitle("行距 ${"%.1f".format(lineHeight)}", theme)
            Slider(
                value = lineHeight,
                onValueChange = onLineHeight,
                valueRange = 1.2f..2.6f,
                steps = 6,
                colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
            )
        }
    }
}

@Composable
fun SettingTitle(text: String, theme: ReaderTheme) {
    Text(
        text,
        fontSize = 13.sp,
        color = theme.sub,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}
