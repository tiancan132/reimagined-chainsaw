package com.duyue.reader.floatwin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 桌面歌词悬浮窗（v0.3）。
 *
 * 交互：
 *   - 左右贴边固定，**只能上下拖动**，不会被拖出屏幕
 *   - **点一下歌词条 → 弹出控制面板**（上一句 / 播放暂停 / 下一句 / 关闭）
 *   - 面板会自动收起
 *
 * 显示：
 *   - 长句最多 4 行，超出自动**滚动到当前朗读位置**（不会把句子挤没）
 *   - 已读部分高亮，未读浅色
 */
class LyricOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var lyricBar: LinearLayout? = null
    private var textView: TextView? = null
    private var controlPanel: LinearLayout? = null
    private var playButton: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null

    private var isSpeaking = false
    private var panelAutoHide: Runnable? = null
    /** 背景透明度 0~100（0=全透明） */
    private var bgAlpha = 100

    var onTogglePlay: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null

    /**
     * 设置背景透明度（0~100）。
     * 0   = 完全透明（只有文字）
     * 100 = 完全不透明（默认深色底）
     */
    fun setBgAlpha(alpha: Int) {
        bgAlpha = alpha.coerceIn(0, 100)
        applyBackground()
    }

    /** 统一应用背景（颜色 + 透明度）与文字描边 */
    private fun applyBackground() {
        lyricBar?.let { bar ->
            val a = (bgAlpha.coerceIn(0, 100) * 255 / 100)
            bar.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(a, 0x1C, 0x1C, 0x1C))
                // 半透明或全透明时不要再画白边，否则像框子
                if (bgAlpha >= 60) {
                    setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                } else {
                    setStroke(0, Color.TRANSPARENT)
                }
            }
        }
        applyTextStyle()
    }

    /** 应用文字描边（固定白色文字） */
    private fun applyTextStyle() {
        textView?.let { tv ->
            tv.setTextColor(Color.WHITE)
            // 背景越透明，越需要黑边把字从背景里"抠"出来
            if (bgAlpha >= 60) {
                tv.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            } else {
                tv.setShadowLayer(10f, 0f, 0f, Color.BLACK)
            }
            tv.invalidate()
        }
    }

    /** 兼容旧调用：等价于 setBgAlpha(100) 或 setBgAlpha(0) */
    fun setBgOpaque(opaque: Boolean) {
        setBgAlpha(if (opaque) 100 else 0)
    }

    companion object {
        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true
    }

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        if (!canDraw(context)) return

        val container = FrameLayout(context).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        // ---- 歌词条 ----
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minimumHeight = dp(52)
        }
        lyricBar = bar

        val tv = TextView(context).apply {
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.3f)
            isVerticalScrollBarEnabled = false
            // 不设置 movementMethod，并且不可点击 —— 让触摸事件
            // 全部冒泡到外层 bar 的 setOnTouchListener（拖动/点按才能生效）
            isClickable = false
            isFocusable = false
            isLongClickable = false
            isSingleLine = false
            // 最多 4 行，避免超长句把窗口撑满屏幕
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textView = tv
        // 按当前背景透明度应用样式
        applyBackground()

        bar.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ---- 控制面板（默认隐藏）----
        val panel = buildControlPanel()
        panel.visibility = View.GONE
        controlPanel = panel

        container.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                // 初始位置，实际显示时会根据歌词条高度动态调整
                topMargin = dp(74)
            }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (screenHeight() * 0.18f).toInt()
        }
        params = lp

        // ---- 触摸：拖动 + 点击弹面板 ----
        var downY = 0f
        var startY = 0
        var moved = false
        var downTime = 0L

        bar.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    startY = lp.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.y = (startY + dy).coerceIn(0, maxTopY())
                        lp.x = 0
                        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!moved && elapsed < 400) {
                        toggleControlPanel()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(container, lp)
            root = container
        } catch (e: Exception) {
            root = null
        }
    }

    /** 控制面板：上一句 / 播放暂停 / 下一句 / 下一章 / 关闭 */
    private fun buildControlPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#F21C1C1C"))
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
        }

        fun button(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton =
            ImageButton(context).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                setOnClickListener {
                    onClick()
                    resetAutoHide()
                }
            }

        val prev = button(android.R.drawable.ic_media_previous, "上一句") { onPrev?.invoke() }
        val play = button(android.R.drawable.ic_media_pause, "播放/暂停") { onTogglePlay?.invoke() }
        playButton = play
        val next = button(android.R.drawable.ic_media_next, "下一句") { onNext?.invoke() }
        val close = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "关闭"
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#FF7B7B"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            setOnClickListener { closeFromPanel() }
        }

        panel.addView(prev)
        panel.addView(play)
        panel.addView(next)
        panel.addView(close)
        return panel
    }

    /** 显示/收起控制面板 */
    private fun toggleControlPanel() {
        val panel = controlPanel ?: return
        if (panel.visibility == View.VISIBLE) {
            hidePanel()
        } else {
            // 动态定位：面板紧贴歌词条下方，不遮挡文字
            val bar = lyricBar
            if (bar != null) {
                val lp = panel.layoutParams
                if (lp is FrameLayout.LayoutParams) {
                    lp.topMargin = bar.height + dp(8)
                    panel.layoutParams = lp
                }
            }
            panel.visibility = View.VISIBLE
            // 面板出现后，确保整体不出屏幕
            clampPosition()
            resetAutoHide()
        }
    }

    private fun resetAutoHide() {
        val runnable = Runnable { hidePanel() }
        panelAutoHide = runnable
        root?.postDelayed(runnable, 5000)
    }

    private fun hidePanel() {
        panelAutoHide?.let { root?.removeCallbacks(it) }
        controlPanel?.visibility = View.GONE
    }

    private fun closeFromPanel() {
        onClose?.invoke()
        // 交给外部决定是否 hide()，这里先收起面板
        hidePanel()
    }

    fun hide() {
        hidePanel()
        val v = root ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        root = null
        lyricBar = null
        textView = null
        controlPanel = null
        playButton = null
    }

    /** 同步播放状态到面板按钮图标 */
    fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        playButton?.setImageResource(
            if (speaking) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    /**
     * 更新歌词。
     *
     * 传入的是「朗读小片段」（按标点切成 8~35 字），最多 1~2 行，
     * 所以这里只做文本替换 + 高亮，**不需要任何滚动或平移**。
     */
    fun update(sentence: String, start: Int, end: Int, accent: Int = 0xFF6EC1FF.toInt()) {
        val tv = textView ?: return
        if (sentence.isBlank()) return

        val s = SpannableString(sentence)
        if (end > start && start >= 0) {
            val e = end.coerceAtMost(sentence.length)
            if (start < e) {
                s.setSpan(ForegroundColorSpan(accent), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            // 未开始朗读到具体字时，整句用略暗的白色（固定白字主题）
            s.setSpan(
                ForegroundColorSpan(Color.parseColor("#C8FFFFFF")),
                0, sentence.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tv.text = s
        tv.translationY = 0f

        root?.post { clampPosition() }
    }

    /** 立即用当前句子填充一次（解决刚显示窗口时空白的问题） */
    fun refreshNow(sentence: String, start: Int, end: Int) {
        if (sentence.isBlank()) return
        update(sentence, start, end)
    }

    /** 把窗口限制在屏幕内 */
    private fun clampPosition() {
        val p = params ?: return
        val v = root ?: return
        v.post {
            val maxY = maxTopY()
            val newY = p.y.coerceIn(0, maxY)
            if (newY != p.y) {
                p.y = newY
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
    }

    private fun maxTopY(): Int {
        val screenH = screenHeight()
        val viewH = root?.height?.takeIf { it > 0 }
            ?: (90 * context.resources.displayMetrics.density).toInt()
        return (screenH - viewH).coerceAtLeast(0)
    }

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int {
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }
}
