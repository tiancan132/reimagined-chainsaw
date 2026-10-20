package com.duyue.reader

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.duyue.reader.ai.AiSummaryClient
import com.duyue.reader.data.LibraryStore
import com.duyue.reader.data.Prefs
import com.duyue.reader.data.SummaryCache
import com.duyue.reader.floatwin.LyricOverlay
import com.duyue.reader.model.Book
import com.duyue.reader.model.BookFormat
import com.duyue.reader.model.Chapter
import com.duyue.reader.parser.EpubParser
import com.duyue.reader.parser.FileReaderUtil
import com.duyue.reader.parser.PdfParser
import com.duyue.reader.parser.TxtParser
import com.duyue.reader.textaction.TextActionHandler
import com.duyue.reader.tts.SpeechPosition
import com.duyue.reader.tts.TtsController
import com.duyue.reader.tts.TtsEvent
import com.duyue.reader.tts.TtsPlaybackService
import com.duyue.reader.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

private sealed interface Route {
    data object Shelf : Route
    data class Read(val book: Book, val chapterIndex: Int) : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val store = remember { LibraryStore(context) }
    val cache = remember { SummaryCache(context) }
    val aiClient = remember { AiSummaryClient(context) }

    var toast by remember { mutableStateOf<String?>(null) }
    var books by remember { mutableStateOf(store.loadIndex()) }
    var route by remember { mutableStateOf<Route>(Route.Shelf) }

    // 启动时自动重新分章：修复旧版本按字数硬切的书
    LaunchedEffect(Unit) {
        if (books.isEmpty()) return@LaunchedEffect
        val needReparse = books.filter { looksLikeFallbackSplit(it) }
        if (needReparse.isEmpty()) return@LaunchedEffect
        val reparsed = books.map { b ->
            if (b.id in needReparse.map { it.id }) {
                reparseBook(context, b).getOrNull() ?: b
            } else b
        }
        books = reparsed
        store.saveIndex(reparsed)
        toast = "已为 ${needReparse.size} 本书重新识别章节"
    }

    var theme by remember { mutableStateOf(ReaderTheme.of(prefs.theme)) }
    var fontSize by remember { mutableStateOf(prefs.fontSize) }
    var lineHeight by remember { mutableStateOf(prefs.lineHeight) }

    // TTS
    val tts = remember { TtsController(context) }
    val ttsReady by tts.ready.collectAsState()
    val speaking by tts.speaking.collectAsState()
    val pausedState by tts.paused.collectAsState()
    val position by tts.position.collectAsState()
    val engines by tts.engines.collectAsState()

    var showChapterList by remember { mutableStateOf(false) }
    var showFontSheet by remember { mutableStateOf(false) }
    var showAiSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTextTargets by remember { mutableStateOf(false) }

    // 选中文字 & 悬浮窗行数
    var selectedText by remember { mutableStateOf("") }
    var pausedBySelection by remember { mutableStateOf(false) }
    var lyricBgAlpha by remember { mutableStateOf(prefs.lyricBgAlpha) }

    // 语速 / 音调
    var rate by remember { mutableStateOf(prefs.ttsRate) }
    var pitch by remember { mutableStateOf(prefs.ttsPitch) }

    // 语速音调变化时实时下发到 TTS
    LaunchedEffect(rate) { tts.setRate(rate) }
    LaunchedEffect(pitch) { tts.setPitch(pitch) }

    // AI 状态
    var aiText by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    // 导入中
    var importing by remember { mutableStateOf(false) }

    // 章末续读标记：为 true 时，进入新章节会自动开始朗读
    var autoPlayNext by remember { mutableStateOf(false) }
    // 当前正在朗读的书与章节，独立于页面路由（回书架后仍然保留）
    var lastReading by remember { mutableStateOf<Pair<Book, Int>?>(null) }
    // TTS 里当前实际装载的是哪一章（用于判断是否需要重新 load 句子）。
    // 不能复用 lastReading：章末自动续读会提前把它改成下一章，导致新章节的 load 被跳过。
    var loadedChapterId by remember { mutableStateOf<String?>(null) }

    // 悬浮歌词
    val overlay = remember { LyricOverlay(context) }
    /**
     * 悬浮窗唯一状态源。
     * 之前同时存在 overlayOn 变量与 prefs.lyricOverlayOn，二者经常不同步，
     * 导致「关掉后下次播放又自己弹出来」「开关图标不刷新」等问题。
     * 现在统一用这一个 Compose 状态，并实时回写 prefs。
     */
    var overlayVisible by remember { mutableStateOf(false) }

    fun showOverlay() {
        if (!LyricOverlay.canDraw(context)) return
        overlay.setBgAlpha(prefs.lyricBgAlpha)
        overlay.show()
        overlay.refreshNow(position.sentenceText, position.charStart, position.charEnd)
        overlayVisible = true
        prefs.lyricOverlayOn = true
    }

    fun hideOverlay() {
        overlay.hide()
        overlayVisible = false
        prefs.lyricOverlayOn = false
    }

    /**
     * 跳到下一章并继续朗读。
     * 供悬浮窗「下一章」按钮和播放栏按钮共用。
     */
    fun goToNextChapter() {
        val current = route as? Route.Read ?: return
        val next = current.chapterIndex + 1
        if (next >= current.book.chapters.size) {
            toast = "已经是最后一章了"
            return
        }
        autoPlayNext = true
        route = Route.Read(current.book, next)
    }

    /** 跳到上一章并从开头朗读 */
    fun goToPrevChapter() {
        val current = route as? Route.Read ?: return
        val prev = current.chapterIndex - 1
        if (prev < 0) {
            toast = "已经是第一章了"
            return
        }
        autoPlayNext = true
        route = Route.Read(current.book, prev)
    }

    // 悬浮窗控制面板的回调
    DisposableEffect(overlay) {
        overlay.onTogglePlay = {
            if (tts.speaking.value) tts.pause()
            else if (tts.paused.value) tts.resume()
            else tts.play()
        }
        overlay.onPrev = { tts.prevSentence() }
        overlay.onNext = { tts.nextSentence() }
        overlay.onNextChapter = {
            // 悬浮窗「下一章」：跳到下一章并继续朗读
            goToNextChapter()
        }
        overlay.onClose = {
            // 从控制面板关闭：只隐藏，不改变偏好设置，
            // 这样下次点播放仍会自动出现；要永久关闭请用设置里的开关。
            overlay.hide()
            overlayVisible = false
        }
        overlay.onOpenApp = {
            // 点歌词条本身 → 回到 App
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
        onDispose {
            overlay.onTogglePlay = null
            overlay.onPrev = null
            overlay.onNext = null
            overlay.onNextChapter = null
            overlay.onClose = null
            overlay.onOpenApp = null
        }
    }

    // 同步播放状态到悬浮窗按钮图标
    LaunchedEffect(speaking) { overlay.setSpeaking(speaking) }

    DisposableEffect(Unit) {
        tts.initialize(prefs.ttsEngine.ifBlank { null })
        // 把同一个控制器交给前台服务，保证 UI 与服务共享同一份朗读状态
        TtsPlaybackService.sharedController = tts
        onDispose {
            TtsPlaybackService.sharedController = null
            tts.release()
        }
    }

    // 悬浮窗文字随朗读更新；同步刷新通知栏
    // 注意：只判断窗口是否存在，不受 overlayOn 影响，避免「开关状态为关但窗口还在」时不刷新文字
    LaunchedEffect(position, speaking) {
        if (overlay.isShowing) {
            overlay.update(position.sentenceText, position.charStart, position.charEnd)
        }
        // 朗读中时，把当前句同步到通知栏
        if (speaking && position.sentenceText.isNotBlank()) {
            TtsPlaybackService.currentSentence = position.sentenceText
        }
        // 自动保存阅读进度：记住这一章读到第几个片段
        // 关键：用 lastReading（独立于 route，记录了正在读的书和章节），
        // 这样退到书架后听书继续跑，进度依然能持续保存。
        val lr = lastReading
        if (lr != null && position.sentenceIndex >= 0) {
            val (book, chapterIdx) = lr
            prefs.saveChapterPos(book.id, chapterIdx, position.sentenceIndex)
            // 同时把「当前章」同步到书架索引，回来看书会定位到正在读的章
            if (book.lastReadChapter != chapterIdx) {
                val updated = books.map {
                    if (it.id == book.id) it.copy(lastReadChapter = chapterIdx) else it
                }
                books = updated
                store.saveIndex(updated)
            }
        }
    }

    /**
     * 章末自动续读（提到 App 顶层）。
     *
     * 放在这里而不是阅读页里，是因为用户要求「回书架也要继续读」——
     * 阅读页销毁后，如果续读逻辑还挂在页面上就会失效。
     *
     * 所以这里不依赖 route，而是记住「当前正在朗读的是哪本书、哪一章」。
     */
    LaunchedEffect(Unit) {
        tts.events.collect { event ->
            if (event !is TtsEvent.AllDone) return@collect
            val cur = lastReading ?: return@collect
            val next = cur.second + 1
            if (next >= cur.first.chapters.size) return@collect
            autoPlayNext = true
            lastReading = cur.first to next
            route = Route.Read(cur.first, next)
        }
    }

    // 导入文件选择器
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            val result = withContext(Dispatchers.IO) { importBook(context, uri) }
            importing = false
            result.onSuccess { book ->
                books = books + book
                store.saveIndex(books)
                store.saveBook(book)
                toast = "已导入《${book.title}》，共 ${book.chapters.size} 章"
            }.onFailure { e ->
                toast = "导入失败：${e.message}"
            }
        }
    }

    // 悬浮窗权限
    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (LyricOverlay.canDraw(context)) {
            showOverlay()
        } else {
            toast = "未授予悬浮窗权限，桌面歌词无法显示"
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            toast = null
        }
    }

    /**
     * 统一的返回逻辑（含实体返回键 / 全面屏手势）。
     *
     * 优先级：先关弹窗 → 再退阅读页 → 最后才退出应用。
     * 之前只给左上角箭头绑了事件，实体返回键直接 finish() 了整个 Activity。
     */
    fun handleBack() {
        when {
            importing -> Unit                                  // 导入中忽略
            showSettings -> showSettings = false
            showTextTargets -> showTextTargets = false
            showAiSheet -> showAiSheet = false
            showFontSheet -> showFontSheet = false
            showChapterList -> showChapterList = false
            selectedText.isNotBlank() -> selectedText = ""     // 先收起选中操作条
            route is Route.Read -> {
                val r = route as Route.Read
                // 保存阅读进度
                val updated = books.map {
                    if (it.id == r.book.id) it.copy(lastReadChapter = r.chapterIndex) else it
                }
                books = updated
                store.saveIndex(updated)
                // 注意：这里**不停朗读、也不隐藏悬浮窗** —— 用户要求「除非主动暂停，否则一直读下去」。
                // 回书架后 TTS 继续在后台读，悬浮窗和通知栏照常显示当前句子。
                route = Route.Shelf
            }
            else -> { /* 在书架：交给系统退出 */ }
        }
    }

    // 接管系统返回键 / 返回手势
    BackHandler(enabled = route is Route.Read || showSettings || showTextTargets ||
        showAiSheet || showFontSheet || showChapterList || selectedText.isNotBlank()) {
        handleBack()
    }

    Box {
        when (val r = route) {
            is Route.Shelf -> {
                BookshelfScreen(
                    books = books,
                    theme = theme,
                    onOpenBook = { b ->
                        // 索引里只有章节标题，打开时再加载这本书的正文
                        var loaded = b
                        if (b.chapters.all { it.content.isEmpty() }) {
                            loaded = store.loadContent(b.id, b)
                        }
                        route = Route.Read(
                            loaded,
                            b.lastReadChapter.coerceIn(0, maxOf(0, loaded.chapters.size - 1))
                        )
                    },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onOpenSettings = { showSettings = true },
                    onDeleteBook = { b ->
                        // 删除书架索引 + 该书正文文件
                        store.deleteBook(b.id)
                        books = books.filterNot { it.id == b.id }
                        store.saveIndex(books)
                        toast = "已删除《${b.title}》"
                    }
                )
            }

            is Route.Read -> {
                val chapter = r.book.chapters.getOrNull(r.chapterIndex) ?: Chapter(0, "空章节", "")
                // 进入章节时装载句子
                LaunchedEffect(r.book.id, r.chapterIndex) {
                    // 用「TTS 实际装载的那一章」来判断，而不是 lastReading。
                    // 章末自动续读会把 lastReading 提前改成下一章，
                    // 用 lastReading 判断会导致新章节的 load 被跳过 —— 这正是
                    // 「界面跳到下一章，声音还是上一章」的根因。
                    val key = "${r.book.id}#${r.chapterIndex}"
                    if (loadedChapterId != key) {
                        tts.stop()
                        lastReading = r.book to r.chapterIndex
                        val savedPos = prefs.getChapterPos(r.book.id, r.chapterIndex)
                            .coerceIn(0, maxOf(0, chapter.sentences.size - 1))
                        tts.load(chapter.sentences, savedPos)
                        loadedChapterId = key
                        overlay.update("", 0, 0)
                    }
                    val cached = withContext(Dispatchers.IO) {
                        cache.get(r.book.id, r.chapterIndex)
                    }
                    aiText = cached.orEmpty()
                    aiError = null
                    aiLoading = false
                }

                // 章末自动续读：等新章节的句子装载完再开播
                LaunchedEffect(autoPlayNext, ttsReady, loadedChapterId) {
                    if (autoPlayNext && ttsReady &&
                        loadedChapterId == "${r.book.id}#${r.chapterIndex}"
                    ) {
                        autoPlayNext = false
                        tts.play(0)
                    }
                }

                ReaderScreen(
                    bookTitle = r.book.title,
                    chapter = chapter,
                    theme = theme,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    position = position,
                    isSpeaking = speaking,
                    isPaused = pausedState,
                    ttsReady = ttsReady,
                    // 上次读到的片段下标：进章节时直接滚到这里，不用点播放
                    initialClipIndex = prefs.getChapterPos(r.book.id, r.chapterIndex),
                    onBack = { handleBack() },
                    onChapterList = { showChapterList = true },
                    onFontSettings = { showFontSheet = true },
                    onPlayPause = {
                        when {
                            speaking -> tts.pause()
                            pausedState -> tts.resume()
                            else -> {
                                // 依据用户偏好自动显示悬浮窗（关闭过一次就不会再烦人）
                                if (prefs.lyricOverlayOn && !overlay.isShowing) {
                                    showOverlay()
                                }
                                // 启动前台服务，保证切后台/锁屏后继续朗读
                                TtsPlaybackService.start(context, r.book.title, chapter.title)
                                tts.play()
                            }
                        }
                    },
                    onStop = {
                        tts.stop()
                        TtsPlaybackService.stop(context)
                    },
                    onPrevSentence = { tts.prevSentence() },
                    onNextSentence = { tts.nextSentence() },
                    onNextChapter = { goToNextChapter() },
                    onPrevChapter = { goToPrevChapter() },
                    onSelectionAction = { action, text ->
                        when {
                            action == "copy" -> {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("读阅", text)
                                )
                                toast = "已复制"
                            }
                            action == "search" -> {
                                val url = "https://www.baidu.com/s?wd=" + Uri.encode(text)
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            action.startsWith("target:") -> {
                                // 用户在设置里配置的 App：key 形如 "pkg/activity"
                                val key = action.removePrefix("target:")
                                val parts = key.split("/")
                                val pkg = parts.firstOrNull().orEmpty()
                                val act = parts.getOrNull(1).orEmpty()
                                val ok = sendToApp(context, pkg, act, text)
                                if (!ok) {
                                    copyText(context, text)
                                    toast = "该应用无法接收文字，已复制，去粘贴即可"
                                }
                            }
                        }
                    },
                    selectionTargets = prefs.textTargets.mapNotNull { encoded ->
                        val p = encoded.split("|")
                        if (p.size >= 4) "${p[2]}/${p[3]}" to p[1] else null
                    },
                    onSentenceClick = { idx -> tts.seekTo(idx) },
                    overlayVisible = overlayVisible,
                    onToggleOverlay = {
                        if (overlayVisible) {
                            hideOverlay()
                        } else if (LyricOverlay.canDraw(context)) {
                            showOverlay()
                        } else {
                            // 没授权，引导去开权限
                            overlayPermLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    },
                    chapterIndex = r.chapterIndex,
                    chapterCount = r.book.chapters.size,
                    hasNextChapter = r.chapterIndex + 1 < r.book.chapters.size,
                    onOpenAiSummary = {
                        showAiSheet = true
                        if (aiText.isBlank() && !aiLoading) aiError = null
                    }
                )

                if (showChapterList) {
                    ChapterListSheet(
                        chapters = r.book.chapters,
                        current = r.chapterIndex,
                        theme = theme,
                        onSelect = { idx ->
                            showChapterList = false
                            tts.stop()
                            route = Route.Read(r.book, idx)
                        },
                        onDismiss = { showChapterList = false }
                    )
                }

                if (showFontSheet) {
                    FontSheet(
                        theme = theme,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        onTheme = {
                            theme = it
                            prefs.theme = it.ordinal
                        },
                        onFontSize = {
                            fontSize = it
                            prefs.fontSize = it
                        },
                        onLineHeight = {
                            lineHeight = it
                            prefs.lineHeight = it
                        },
                        onDismiss = { showFontSheet = false }
                    )
                }

                if (showAiSheet) {
                    AiSummarySheet(
                        theme = theme,
                        chapterTitle = chapter.title,
                        hasKey = prefs.apiKey.isNotBlank(),
                        isLoading = aiLoading,
                        text = aiText,
                        error = aiError,
                        onGenerate = {
                            if (prefs.apiKey.isBlank()) return@AiSummarySheet
                            aiLoading = true
                            aiError = null
                            aiText = ""
                            (context as? ComponentActivity)?.lifecycleScope?.launch {
                                try {
                                    val sb = StringBuilder()
                                    aiClient.summarizeStream(
                                        apiKey = prefs.apiKey,
                                        bookTitle = r.book.title,
                                        chapterTitle = chapter.title,
                                        chapterContent = chapter.content,
                                        model = prefs.aiModel
                                    ) { delta ->
                                        sb.append(delta)
                                        aiText = sb.toString()
                                    }
                                    aiLoading = false
                                    withContext(Dispatchers.IO) {
                                        cache.put(r.book.id, r.chapterIndex, sb.toString())
                                    }
                                } catch (e: Exception) {
                                    aiLoading = false
                                    aiError = e.message ?: "生成失败"
                                }
                            }
                        },
                        onClear = {
                            (context as? ComponentActivity)?.lifecycleScope?.launch {
                                withContext(Dispatchers.IO) {
                                    cache.clear(r.book.id, r.chapterIndex)
                                }
                                aiText = ""
                                aiError = null
                            }
                        },
                        onOpenSettings = {
                            showAiSheet = false
                            showSettings = true
                        },
                        onCopyAll = {
                            if (aiText.isBlank()) {
                                toast = "还没有内容可复制"
                            } else {
                                TextActionHandler.copyToClipboard(context, aiText)
                                toast = "已复制整份 AI 摘要"
                            }
                        },
                        onDismiss = { showAiSheet = false }
                    )
                }
            }
        }

        if (showSettings) {
            SettingsSheet(
                theme = theme,
                prefs = prefs,
                engines = engines,
                onThemeChange = {
                    theme = it
                    prefs.theme = it.ordinal
                },
                onDismiss = { showSettings = false },
                onToast = { toast = it },
                overlayEnabled = overlayVisible,
                onToggleOverlay = { enable ->
                    if (enable) {
                        if (LyricOverlay.canDraw(context)) {
                            showOverlay()
                        } else {
                            overlayPermLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    } else {
                        hideOverlay()
                    }
                },
                onEngineChange = { pkg ->
                    prefs.ttsEngine = pkg
                    tts.setEngine(pkg)
                    toast = "已切换 TTS 引擎，正在重新初始化"
                }

                ,
                ttsRate = rate,
                ttsPitch = pitch,
                onTtsRate = {
                    rate = it
                    prefs.ttsRate = it
                },
                onTtsPitch = {
                    pitch = it
                    prefs.ttsPitch = it
                },
                lyricBgAlpha = lyricBgAlpha,
                onLyricBgAlpha = {
                    lyricBgAlpha = it
                    prefs.lyricBgAlpha = it
                    overlay.setBgAlpha(it)
                },
                onOpenTextTargets = {
                    showSettings = false
                    showTextTargets = true
                },
                onFetchModels = { key, callback ->
                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                        try {
                            val models = aiClient.fetchModels(key)
                            callback(ModelFetchResult.Success(models))
                        } catch (e: Exception) {
                            callback(ModelFetchResult.Failure(e.message ?: "获取失败，可手动填写模型名"))
                        }
                    }
                },
                onTestConnection = { key, modelName, callback ->
                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                        val (ok, msg) = aiClient.testConnection(key, modelName)
                        callback(ok, msg)
                    }
                }
            )
        }

        if (showTextTargets) {
            TextTargetSheet(
                prefs = prefs,
                theme = theme,
                onDismiss = { showTextTargets = false }
            )
        }

        if (importing) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(androidx.compose.ui.graphics.Color(0x88000000)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = theme.accent)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
                    Text("正在解析书籍…", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

/** 导入一本书：识别格式 → 解析 → 生成 Book */
private fun importBook(context: android.content.Context, uri: Uri): Result<Book> = runCatching {
    // 持久化读取权限，避免重启后失效
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    val name = FileReaderUtil.queryDisplayName(context, uri)
    val format = FileReaderUtil.detectFormat(name)
        ?: throw IllegalStateException("不支持的文件类型，请选择 TXT / EPUB / PDF")

    val id = UUID.randomUUID().toString().take(8)
    val baseTitle = name.substringBeforeLast('.')

    var author = "未知"
    val chapters: List<Chapter>
    var title = baseTitle

    when (format) {
        BookFormat.TXT -> {
            val text = FileReaderUtil.readText(context, uri)
            if (text.isBlank()) throw IllegalStateException("文件内容为空")
            // TXT 尝试从开头几行里认出作者
            author = extractAuthorFromTxt(text) ?: "未知"
            chapters = TxtParser.parse(baseTitle, text)
        }
        BookFormat.EPUB -> {
            val res = EpubParser.parse(context, uri)
            title = res.title.ifBlank { baseTitle }
            author = res.author.ifBlank { "未知" }
            chapters = res.chapters
        }
        BookFormat.PDF -> {
            val count = PdfParser.pageCount(context, uri)
            if (count == 0) throw IllegalStateException("PDF 无法打开或已加密")
            chapters = PdfParser.buildChapters(count)
        }
    }

    if (chapters.isEmpty()) throw IllegalStateException("未能解析出章节内容")

    Book(
        id = id,
        title = title,
        author = author,
        format = format,
        sourceUri = uri.toString(),
        chapters = chapters
    )
}

/** 从 TXT 开头几行里找「作者：xxx」这类信息 */
private fun extractAuthorFromTxt(text: String): String? {
    val head = text.take(600)
    val patterns = listOf(
        Regex("[作著]\\s*[者家]\\s*[:：]\\s*(.{1,20})"),
        Regex("作者\\s*[:：]?\\s*(.{1,20})"),
    )
    for (p in patterns) {
        val m = p.find(head) ?: continue
        val v = m.groupValues[1]
            .lineSequence().first()
            .trim().trimEnd('　', ' ', '|', '、', '，', ',')
        if (v.isNotBlank() && v.length >= 2 && !v.contains("未知")) return v
    }
    return null
}

/**
 * 判断一本书是不是被「按字数硬切」的（旧版本分章失败的标志）。
 * 特征：章节数 > 1 且有一半以上的标题形如「书名（第 N 部分）」。
 */
private fun looksLikeFallbackSplit(book: Book): Boolean {
    if (book.chapters.size <= 1) return false
    val fallbackCount = book.chapters.count { it.title.contains("部分）") }
    return fallbackCount >= book.chapters.size / 2.0
}

/**
 * 用新的分章规则重新解析一本已在书架里的书。
 * 只重算章节结构，保留 id 与阅读进度。
 */
private fun reparseBook(context: android.content.Context, book: Book): Result<Book> = runCatching {
    val uri = Uri.parse(book.sourceUri)
    val baseTitle = book.title

    val chapters = when (book.format) {
        BookFormat.TXT -> {
            val text = FileReaderUtil.readText(context, uri)
            TxtParser.parse(baseTitle, text)
        }
        BookFormat.EPUB -> {
            EpubParser.parse(context, uri).chapters
        }
        BookFormat.PDF -> PdfParser.buildChapters(PdfParser.pageCount(context, uri))
    }

    if (chapters.isEmpty()) throw IllegalStateException("重新解析未得到章节")

    book.copy(
        chapters = chapters,
        lastReadChapter = book.lastReadChapter.coerceIn(0, chapters.size - 1)
    )
}

/**
 * 章末自动续读。
 *
 * 监听 TTS 的 AllDone 事件：本章最后一句读完后，如果还有下一章，
 * 自动切换到下一章并**继续朗读**（用户要求：读完一页/一章自动往下走）。
 *
 * AllDone 之后立刻 tts.stop() 会触发一次 onStop 回调，所以这里用 remember
 * 标记「本章已续读」，避免重复触发导致连跳多章。
 */
@Composable
private fun AutoAdvanceEffect(
    tts: TtsController,
    chapterIndex: Int,
    hasNext: Boolean,
    onNextChapter: (Int) -> Unit
) {
    var advanced by remember(chapterIndex) { mutableStateOf(false) }

    LaunchedEffect(chapterIndex, hasNext) {
        tts.events.collect { event ->
            if (event is TtsEvent.AllDone && hasNext && !advanced) {
                advanced = true
                onNextChapter(chapterIndex + 1)
            }
        }
    }
}

// ---------------------------------------------------------------- 选中文字后的外部跳转

/** DeepSeek 的常见包名 */
private val DEEPSEEK_PKGS = listOf("com.deepseek.chat", "com.deepseek.mobile")
/** 豆包的常见包名 */
private val DOUBAO_PKGS = listOf("com.larus.nova", "com.volcengine.doubao")

/** 复制文字到剪贴板 */
private fun copyText(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("读阅", text))
}

/**
 * 把选中文字发给指定的 App。
 *
 * 依次尝试：
 *   1) ACTION_PROCESS_TEXT（「处理文字」标准接口，DeepSeek/豆包/词典多支持）
 *   2) ACTION_SEND（分享文字）
 * 两者都不成就返回 false，由调用方降级为复制。
 */
private fun sendToApp(
    context: android.content.Context,
    pkg: String,
    activity: String,
    text: String
): Boolean {
    if (pkg.isBlank()) return false
    val pm = context.packageManager

    fun tryIntent(intent: Intent): Boolean {
        if (pm.resolveActivity(intent, 0) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    // 1) PROCESS_TEXT
    val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (activity.isNotBlank() && activity != "null") {
            setClassName(pkg, activity)
        } else {
            setPackage(pkg)
        }
    }
    if (tryIntent(process)) return true

    // 2) ACTION_SEND
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(pkg)
    }
    if (tryIntent(share)) return true

    return false
}

/**
 * 尝试打开指定的 AI App 并把文字带过去。
 *
 * 优先用 ACTION_PROCESS_TEXT（支持「处理文字」的 App 都能接住），
 * 退而求其次用 ACTION_SEND 分享文本，最后若包不存在返回 false。
 */
private fun launchAiApp(
    context: android.content.Context,
    text: String,
    packageCandidates: List<String>
): Boolean {
    val pm = context.packageManager

    // 1) 优先尝试 PROCESS_TEXT 并指定包名
    for (pkg in packageCandidates) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (pm.resolveActivity(intent, 0) != null) {
            runCatching { context.startActivity(intent) }.onSuccess { return true }
        }
    }

    // 2) 退化为分享文本
    for (pkg in packageCandidates) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (pm.resolveActivity(share, 0) != null) {
            runCatching { context.startActivity(share) }.onSuccess { return true }
        }
    }

    return false
}
