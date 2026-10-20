package com.duyue.reader.textaction

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 文字处理动作（ACTION_PROCESS_TEXT）。
 *
 * 安卓标准机制：任何声明了
 *   <action android:name="android.intent.action.PROCESS_TEXT" />
 *   <category android:name="android.intent.category.DEFAULT" />
 *   android:mimeType="text/plain"
 * 的 App（DeepSeek、豆包、各类词典、翻译）都能接收「处理选中文字」的请求。
 *
 * 我们不做内置 AI/词典，只负责把文字交给用户指定的 App。
 */
object TextActionHandler {

    /** 一个可用的外部 App */
    data class TargetApp(
        val label: String,
        val packageName: String,
        val activityName: String
    ) {
        /** 稳定标识，用于存进设置 */
        val key: String get() = "$packageName/$activityName"
    }

    /** 构造「处理这段文字」的意图 */
    private fun buildIntent(text: String, isReadOnly: Boolean = true): Intent =
        Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, isReadOnly)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 枚举手机里所有能「接收文字」的 App。
     *
     * 关键：不只查 ACTION_PROCESS_TEXT（豆包等很多 App 不支持），
     * 还查 ACTION_SEND（几乎都支持分享文字）。两者结果合并去重。
     * 这样豆包、微信等都能加入快捷条。
     */
    fun listAvailableApps(context: Context): List<TargetApp> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, TargetApp>()

        fun collect(intent: Intent) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            else
                @Suppress("DEPRECATION") PackageManager.MATCH_ALL
            val infos: List<ResolveInfo> = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    pm.queryIntentActivities(intent, flags as PackageManager.ResolveInfoFlags)
                else
                    @Suppress("DEPRECATION") pm.queryIntentActivities(intent, flags as Int)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in infos) {
                val act = ri.activityInfo ?: continue
                if (act.packageName == context.packageName) continue
                val app = TargetApp(
                    label = ri.loadLabel(pm).toString(),
                    packageName = act.packageName,
                    activityName = act.name
                )
                result.putIfAbsent(app.key, app)
            }
        }

        // 1) 处理文字（最直接）
        collect(
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, "测试")
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            }
        )
        // 2) 分享文字（兜底，豆包等靠这个）
        collect(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "测试")
            }
        )

        return result.values.sortedBy { it.label }
    }

    /**
     * 把选中文字发给指定 App。
     * 依次尝试：PROCESS_TEXT（处理文字）→ ACTION_SEND（分享文字）。
     * 豆包等只支持分享的 App，走第二条路。
     */
    fun sendToApp(context: Context, app: TargetApp, text: String): Boolean {
        val pm = context.packageManager

        fun tryStart(intent: Intent): Boolean {
            if (pm.resolveActivity(intent, 0) == null) return false
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }

        // 1) PROCESS_TEXT
        val process = buildIntent(text).apply {
            if (app.activityName.isNotBlank()) setClassName(app.packageName, app.activityName)
            else setPackage(app.packageName)
        }
        if (tryStart(process)) return true

        // 2) ACTION_SEND（分享）
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (app.activityName.isNotBlank()) setClassName(app.packageName, app.activityName)
            else setPackage(app.packageName)
        }
        if (tryStart(share)) return true

        return false
    }

    /** 用浏览器搜索这段文字 */
    fun searchOnWeb(context: Context, text: String): Boolean = try {
        val url = "https://www.baidu.com/s?wd=" + Uri.encode(text)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    /** 复制到剪贴板 */
    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("读阅", text))
    }
}

/**
 * 已经保存的、用户挑好的目标 App。
 * 用 JSON 存在 Prefs 里，key 是 list。
 */
data class SavedTarget(
    val key: String,
    val label: String,
    val packageName: String,
    val activityName: String
) {
    fun toTargetApp() = TextActionHandler.TargetApp(label, packageName, activityName)
}
