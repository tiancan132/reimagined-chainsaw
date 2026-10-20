package com.duyue.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.duyue.reader.MainActivity
import com.duyue.reader.R

/**
 * 听书前台服务 —— 让朗读在切后台、锁屏后继续。
 *
 * 关键点（安卓 10 / API 29）：
 * 1. startForeground 后进程被视为前台服务，内存优先级高，不易被系统回收
 * 2. foregroundServiceType = mediaPlayback，系统允许长时间运行媒体类服务
 * 3. 通知栏提供「上一句 / 播放暂停 / 下一句」，锁屏也能操作
 * 4. 服务本身不持有 TTS 引擎，只持有同一个 TtsController 实例的引用，
 *    这样 UI 和服务共享同一个朗读状态，不会出现两套声音。
 */
class TtsPlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.duyue.reader.tts.START"
        const val ACTION_PAUSE = "com.duyue.reader.tts.PAUSE"
        const val ACTION_RESUME = "com.duyue.reader.tts.RESUME"
        const val ACTION_STOP = "com.duyue.reader.tts.STOP"
        const val ACTION_PREV = "com.duyue.reader.tts.PREV"
        const val ACTION_NEXT = "com.duyue.reader.tts.NEXT"

        const val EXTRA_TITLE = "title"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_SENTENCE = "sentence"

        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001

        /** 由 App 注入的共享 TTS 控制器（服务与 UI 共用同一个实例） */
        var sharedController: TtsController? = null

        /** 当前朗读句，由 UI 写入，通知栏读取 */
        var currentSentence: String = ""
            set(value) {
                if (field != value) {
                    field = value
                    instance?.let { it.runCatchingRefresh() }
                }
            }

        private var instance: TtsPlaybackService? = null

        private fun TtsPlaybackService.runCatchingRefresh() {
            try {
                refreshNotificationInternal(currentSentence)
            } catch (_: Exception) {}
        }

        fun start(context: Context, title: String, chapter: String) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHAPTER, chapter)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsPlaybackService::class.java))
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply { this.action = action }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }

    private var title: String = "读阅"
    private var chapter: String = ""
    private var lastSentence: String = ""
    private var foregroundStarted = false

    /** 供静态 currentSentence setter 调用 */
    fun refreshNotificationInternal(sentence: String) {
        lastSentence = sentence
        updateNotification(sharedController?.speaking?.value == true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctrl = sharedController

        when (intent?.action) {
            ACTION_START -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
                intent.getStringExtra(EXTRA_CHAPTER)?.let { chapter = it }
                startForegroundCompat(buildNotification())
            }
            ACTION_PAUSE -> {
                ctrl?.pause()
                updateNotification()
            }
            ACTION_RESUME -> {
                ctrl?.resume()
                updateNotification()
            }
            ACTION_PREV -> {
                ctrl?.prevSentence()
                updateNotification()
            }
            ACTION_NEXT -> {
                ctrl?.nextSentence()
                updateNotification()
            }
            ACTION_STOP -> {
                ctrl?.stop()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                // 系统重启服务（START_STICKY 回传 null intent）
                if (!foregroundStarted) startForegroundCompat(buildNotification())
            }
        }
        return START_STICKY
    }

    /** 由 UI 调用：更新通知里的当前朗读句 */
    fun refreshNotification(sentence: String, speaking: Boolean) {
        lastSentence = sentence
        updateNotification(speaking)
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            // 权限或其他情况下退化
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun updateNotification(speaking: Boolean = sharedController?.speaking?.value == true) {
        if (!foregroundStarted) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(speaking))
        }
    }

    private fun buildNotification(speaking: Boolean = sharedController?.speaking?.value == true): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(action: String, icon: Int, label: String): NotificationCompat.Action {
            val pi = PendingIntent.getService(
                this,
                action.hashCode(),
                Intent(this, TtsPlaybackService::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(icon, label, pi)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (chapter.isNotBlank()) chapter else title)
            .setContentText(lastSentence.ifBlank { title })
            .setSubText(title)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(lastSentence.ifBlank { title })
            )
            .addAction(action(ACTION_PREV, android.R.drawable.ic_media_previous, "上一句"))

        if (speaking) {
            builder.addAction(action(ACTION_PAUSE, android.R.drawable.ic_media_pause, "暂停"))
        } else {
            builder.addAction(action(ACTION_RESUME, android.R.drawable.ic_media_play, "继续"))
        }

        builder.addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, "下一句"))
        builder.addAction(action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "停止"))

        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 每次都用最新设置重建 channel（旧版本可能创建过低优先级 channel，不更新会导致通知被折叠）
            val ch = NotificationChannel(
                CHANNEL_ID,
                "听书播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "听书时的播放控制"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                setAllowBubbles(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun stopSelfSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        foregroundStarted = false
        instance = null
        super.onDestroy()
    }
}
