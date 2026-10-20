package com.duyue.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TtsEngineInfo(val label: String, val packageName: String) {
    override fun toString(): String = label
}

/** 正在朗读的位置 */
data class SpeechPosition(
    val sentenceIndex: Int = 0,
    val sentenceText: String = "",
    /** 当前句内的字符区间，用于卡拉OK式高亮；0,0 表示整句高亮 */
    val charStart: Int = 0,
    val charEnd: Int = 0
)

sealed class TtsEvent {
    data class SentenceStart(val index: Int, val text: String) : TtsEvent()
    data class RangeUpdate(val index: Int, val start: Int, val end: Int) : TtsEvent()
    data class SentenceDone(val index: Int) : TtsEvent()
    data object AllDone : TtsEvent()
    data class Error(val index: Int, val code: Int) : TtsEvent()
    data class StateChanged(val speaking: Boolean, val paused: Boolean) : TtsEvent()
}

/**
 * 系统 TTS 封装 —— 本项目「听书 + 字幕」的核心。
 *
 * 设计要点（踩坑后修正）：
 * 1. **一句一提交**：每次只 speak 一句，onRangeStart 的字符偏移天然属于当前句，字幕对齐无歧义。
 * 2. **不用 QUEUE_ADD 预排队**：排队会让 onStart 在下一句真正开始时才触发，
 *    高亮容易跳错句；严格「上句 onDone 后再提交下句」，同步最可靠。
 * 3. **逐字进度**：onRangeStart（API 26+）。并非所有国产引擎都会回调，
 *    所以同时提供按字数比例的估算兜底（见 estimateProgress）。
 * 4. **语句级兜底**：若引擎完全不回调 onRangeStart，则退化为整句高亮，仍可用。
 */
class TtsController(context: Context) {

    private val appContext: Context = context.applicationContext
    private var tts: TextToSpeech? = null
    private var initializing = false

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _engines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val engines: StateFlow<List<TtsEngineInfo>> = _engines.asStateFlow()

    private val _currentEngine = MutableStateFlow("")
    val currentEngine: StateFlow<String> = _currentEngine.asStateFlow()

    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    val voices: StateFlow<List<Voice>> = _voices.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _position = MutableStateFlow(SpeechPosition())
    val position: StateFlow<SpeechPosition> = _position.asStateFlow()

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private val _rate = MutableStateFlow(1.0f)
    val rate: StateFlow<Float> = _rate.asStateFlow()

    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    /** 该引擎是否支持逐字进度回调 */
    private val _supportsRange = MutableStateFlow(false)
    val supportsRange: StateFlow<Boolean> = _supportsRange.asStateFlow()

    private var sentences: List<String> = emptyList()
    private var cursor: Int = 0

    /** 防止 stop() 触发的 onError 被误判为「出错继续」 */
    private var stopping = false

    // ---------------------------------------------------------------- 初始化

    fun initialize(enginePackage: String? = null) {
        if (initializing) return
        initializing = true
        stopping = true
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        _ready.value = false

        val onInit = TextToSpeech.OnInitListener { status ->
            initializing = false
            if (status == TextToSpeech.SUCCESS) {
                _ready.value = true
                refreshEngines()
                configureLanguage()
                tts?.setOnUtteranceProgressListener(progressListener)
                applyRatePitch()
            } else {
                _ready.value = false
                _events.tryEmit(TtsEvent.Error(cursor, status))
            }
        }

        val requested = enginePackage ?: _currentEngine.value.takeIf { it.isNotBlank() }
        tts = if (requested != null) {
            try { TextToSpeech(appContext, onInit, requested) }
            catch (e: Exception) { TextToSpeech(appContext, onInit) }
        } else {
            TextToSpeech(appContext, onInit)
        }
    }

    /** 枚举系统里所有 TTS 引擎：能看到小米、讯飞、华为、Google 等 */
    private fun refreshEngines() {
        val list = try {
            tts?.engines
                ?.map { TtsEngineInfo(it.label ?: it.name, it.name) }
                ?.distinctBy { it.packageName }
                ?.sortedBy { it.label }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        _engines.value = list
        val def = try { tts?.defaultEngine } catch (_: Exception) { null }
        if (def != null) _currentEngine.value = def
    }

    private fun configureLanguage() {
        val t = tts ?: return
        val res = try { t.setLanguage(Locale.SIMPLIFIED_CHINESE) } catch (_: Exception) { -1 }
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            try { t.language = Locale.CHINESE } catch (_: Exception) {}
        }
        _voices.value = try {
            t.voices
                ?.filter { it.locale.language.equals("zh", true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // ---------------------------------------------------------------- 进度回调

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val idx = utteranceId?.toIntOrNull() ?: return
            cursor = idx
            val text = sentences.getOrElse(idx) { "" }
            _speaking.value = true
            _paused.value = false
            _position.value = SpeechPosition(idx, text, 0, 0)
            _events.tryEmit(TtsEvent.SentenceStart(idx, text))
            _events.tryEmit(TtsEvent.StateChanged(true, false))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val idx = utteranceId?.toIntOrNull() ?: return
            if (!_supportsRange.value) _supportsRange.value = true
            val text = sentences.getOrElse(idx) { "" }
            _position.value = SpeechPosition(idx, text, start, end)
            _events.tryEmit(TtsEvent.RangeUpdate(idx, start, end))
        }

        override fun onDone(utteranceId: String?) {
            val idx = utteranceId?.toIntOrNull() ?: return
            _events.tryEmit(TtsEvent.SentenceDone(idx))
            advanceFrom(idx)
        }

        @Deprecated("Deprecated in API 21", ReplaceWith("onError(utteranceId, code)"))
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (stopping) {
                // stop() 引发的回调，忽略
                stopping = false
                return
            }
            val idx = utteranceId?.toIntOrNull() ?: return
            _events.tryEmit(TtsEvent.Error(idx, errorCode))
            // 单句失败不应卡死整章，跳到下一句继续
            advanceFrom(idx)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // 被 stop 打断：不自动推进
        }
    }

    /** 一句结束后推进 */
    private fun advanceFrom(finishedIndex: Int) {
        if (stopping) return
        val next = finishedIndex + 1
        if (next < sentences.size) {
            cursor = next
            speakAt(next)
        } else {
            _speaking.value = false
            _paused.value = false
            _events.tryEmit(TtsEvent.AllDone)
            _events.tryEmit(TtsEvent.StateChanged(false, false))
        }
    }

    // ---------------------------------------------------------------- 控制

    fun load(list: List<String>, startIndex: Int = 0) {
        sentences = list
        cursor = startIndex.coerceIn(0, maxOf(0, list.size - 1))
        _position.value = SpeechPosition(cursor, list.getOrElse(cursor) { "" }, 0, 0)
    }

    fun play(startIndex: Int = cursor) {
        if (sentences.isEmpty()) return
        stopping = false
        val i = startIndex.coerceIn(0, sentences.size - 1)
        cursor = i
        _speaking.value = true
        _paused.value = false
        speakAt(i)
        _events.tryEmit(TtsEvent.StateChanged(true, false))
    }

    private fun speakAt(index: Int) {
        val t = tts ?: return
        if (!_ready.value) return
        if (index >= sentences.size) return

        val text = sentences[index]
        if (text.isBlank()) {
            advanceFrom(index)
            return
        }
        _position.value = SpeechPosition(index, text, 0, 0)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), index.toString())
    }

    fun pause() {
        if (!_speaking.value) return
        stopping = true
        tts?.stop()
        _speaking.value = false
        _paused.value = true
        _events.tryEmit(TtsEvent.StateChanged(false, true))
    }

    fun resume() {
        if (!_paused.value) return
        stopping = false
        play(cursor)
    }

    fun stop() {
        stopping = true
        tts?.stop()
        _speaking.value = false
        _paused.value = false
        cursor = 0
        _position.value = SpeechPosition()
        _events.tryEmit(TtsEvent.StateChanged(false, false))
    }

    /** 跳到指定片段，并**从该片段开始朗读**（未播放时也会启动） */
    fun seekTo(index: Int) {
        if (sentences.isEmpty()) return
        val i = index.coerceIn(0, sentences.size - 1)
        cursor = i
        stopping = false
        runCatching { tts?.stop() }
        _speaking.value = true
        _paused.value = false
        val txt = sentences.getOrElse(i) { "" }
        _position.value = SpeechPosition(i, txt, 0, 0)
        _events.tryEmit(TtsEvent.SentenceStart(i, txt))
        _events.tryEmit(TtsEvent.StateChanged(true, false))
        speakAt(i)
    }

    fun nextSentence() = seekTo(cursor + 1)
    fun prevSentence() = seekTo(cursor - 1)

    val currentSentenceIndex: Int get() = cursor
    val sentenceCount: Int get() = sentences.size

    fun setRate(value: Float) {
        _rate.value = value.coerceIn(0.5f, 3.0f)
        applyRatePitch()
    }

    fun setPitch(value: Float) {
        _pitch.value = value.coerceIn(0.5f, 2.0f)
        applyRatePitch()
    }

    private fun applyRatePitch() {
        try {
            tts?.setSpeechRate(_rate.value)
            tts?.setPitch(_pitch.value)
        } catch (_: Exception) {}
    }

    fun setVoice(voice: Voice) {
        try { tts?.voice = voice } catch (_: Exception) {}
    }

    fun setEngine(pkg: String) {
        _currentEngine.value = pkg
        initialize(pkg)
    }

    fun release() {
        stopping = true
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        _ready.value = false
    }
}
