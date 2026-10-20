package com.paper.nightlock

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.time.ZonedDateTime

object Permissions {
    fun exact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun accessibility(context: Context): Boolean {
        val target = ComponentName(context, GuardService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == target }
    }
    fun missing(context: Context): List<String> = buildList {
        if (!accessibility(context) || GuardService.instance == null) add("开启无障碍应用限制服务")
        if (!exact(context)) add("允许闹钟和提醒（精确定时）")
        if (Repository.get(context).papers().isEmpty()) add("添加至少一篇真实论文摘要")
        if (Prefs(context).selected.isEmpty()) add("勾选至少一个受限应用")
    }
}

object Safety {
    fun neverBlocked(context: Context): Set<String> = buildSet {
        add(context.packageName)
        addAll(listOf("com.android.settings", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.android.incallui", "com.vivo.incallui", "com.google.android.dialer", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.vivo.permissionmanager"))
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let(::add)
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { add(it.packageName) }
    }
}

object Notices {
    fun setup(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("night_lock", "论文夜锁状态", NotificationManager.IMPORTANCE_LOW))
    }
    fun show(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        setup(context)
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, "night_lock").setSmallIcon(com.paper.nightlock.R.drawable.ic_moon)
            .setContentTitle("论文夜锁").setContentText(text).setContentIntent(pending).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(1, notification)
    }
}

object Scheduler {
    private fun pending(context: Context, start: Boolean): PendingIntent = PendingIntent.getBroadcast(context,
        if (start) 1 else 2, Intent(context, ScheduleReceiver::class.java).setAction(if (start) "com.paper.nightlock.START" else "com.paper.nightlock.END"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun sync(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val prefs = Prefs(context)
        val start = pending(context, true); val end = pending(context, false)
        manager.cancel(start); manager.cancel(end)
        if (!prefs.enabled || !Permissions.exact(context) || prefs.selected.isEmpty() || Repository.get(context).papers().isEmpty()) return
        try {
            val now = ZonedDateTime.now()
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prefs.window.next(now, true).toInstant().toEpochMilli(), start)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, prefs.window.next(now, false).toInstant().toEpochMilli(), end)
        } catch (_: SecurityException) { prefs.lastError = "精确定时权限已关闭，请重新授权" }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.sync(context)
        GuardService.instance?.reconcile()
        if (intent.action == "com.paper.nightlock.END") Notices.show(context, "夜间时段已结束，应用已恢复正常使用")
    }
}

class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var repository: Repository
    private var grantedWindow: String? = null
    private var foregroundPackage = ""
    private var challengePaper: Paper? = null
    private var challengeToken: String? = null
    private var overlay: ChallengePane? = null
    private var excluded: Set<String> = emptySet()
    private val changed: () -> Unit = { Scheduler.sync(this); excluded = Safety.neverBlocked(this); reconcile() }
    private val boundaryCheck = object : Runnable {
        override fun run() {
            reconcile()
            val active = ::prefs.isInitialized && prefs.enabled && prefs.window.active(ZonedDateTime.now()) != null
            handler.postDelayed(this, if (active) 1000 else 60000)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                revoke(); hideOverlay(); clearChallenge()
            } else { handler.postDelayed({ reconcile() }, 150) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this); repository = Repository.get(this)
        excluded = Safety.neverBlocked(this)
        revoke()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver, filter)
        Changes.listen(changed)
        Scheduler.sync(this)
        handler.post(boundaryCheck)
        Changes.emit()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // Ignore our overlay and IME windows; neither changes the underlying foreground application.
        val keyboardPackages = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        if (packageName == this.packageName || packageName in keyboardPackages) {
            if (packageName == this.packageName && (overlay == null || event.className?.toString() == MainActivity::class.java.name)) {
                foregroundPackage = packageName
                hideOverlay()
            }
            return
        }
        foregroundPackage = packageName
        reconcile()
    }

    fun reconcile() {
        if (!::prefs.isInitialized) return
        val active = prefs.window.active(ZonedDateTime.now())
        val valid = prefs.enabled && Permissions.exact(this) && prefs.selected.isNotEmpty() && repository.papers().isNotEmpty()
        if (!valid || active == null) { revoke(); hideOverlay(); clearChallenge(); return }
        if (grantedWindow != null && grantedWindow != active.key) revoke()
        if (prefs.lastLockedWindow != active.key) {
            revoke(); hideOverlay(); clearChallenge()
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (locked || performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) {
                prefs.lastLockedWindow = active.key; prefs.lastError = ""
                Notices.show(this, "夜间限制已开始。抄写摘要后可继续使用勾选的应用")
            } else { prefs.lastError = "系统未接受锁屏请求，请在权限页检查服务状态" }
            return
        }
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!screenOn || locked || grantedWindow == active.key || foregroundPackage in excluded || foregroundPackage !in prefs.selected) {
            hideOverlay(); return
        }
        val paper = challengePaper
        if (paper != null && repository.papers().none { it.id == paper.id && it.abstractText == paper.abstractText }) {
            hideOverlay(); clearChallenge()
        }
        if (challengePaper == null) { challengePaper = repository.pick(); challengeToken = Repository.token() }
        if (overlay == null) showOverlay(active.key)
    }

    private fun showOverlay(windowKey: String) {
        val paper = challengePaper ?: return
        val token = challengeToken ?: return
        val pane = ChallengePane(this, paper, false, onExit = {
            hideOverlay(); performGlobalAction(GLOBAL_ACTION_HOME); foregroundPackage = ""
        }, onPassed = {
            if (challengeToken != token || prefs.window.active(ZonedDateTime.now())?.key != windowKey || !prefs.enabled ||
                instance !== this || !Permissions.exact(this) || !getSystemService(PowerManager::class.java).isInteractive ||
                getSystemService(KeyguardManager::class.java).isKeyguardLocked) false
            else {
                val uses = repository.complete(token, paper)
                if (uses == null) false else {
                    grantedWindow = windowKey
                    hideOverlay(); clearChallenge()
                    val message = if (uses >= 3) "已放行至再次锁屏。这篇摘要已使用 $uses 次，建议更换论文库" else "已放行至再次锁屏 · 本篇累计使用 $uses 次"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (uses == 3) Notices.show(this, "《${paper.title}》已成功使用 3 次，建议更换论文库")
                    true
                }
            }
        })
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.graphics.PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "论文夜锁摘要验证"
        }
        try {
            overlay = pane
            getSystemService(WindowManager::class.java).addView(pane, params)
            pane.requestApplyInsets()
        } catch (_: RuntimeException) {
            overlay = null; prefs.lastError = "抄写界面显示失败，请检查系统后台与弹出界面限制"
            Notices.show(this, prefs.lastError)
        }
    }

    private fun hideOverlay() {
        overlay?.let { pane ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(pane.windowToken, 0)
            runCatching { getSystemService(WindowManager::class.java).removeView(pane) }
            pane.dispose()
        }
        overlay = null
    }
    private fun revoke() { grantedWindow = null }
    private fun clearChallenge() { challengePaper = null; challengeToken = null }
    override fun onInterrupt() { revoke(); hideOverlay(); clearChallenge() }
    override fun onUnbind(intent: Intent?): Boolean { cleanUp(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanUp(); super.onDestroy() }
    private fun cleanUp() {
        if (instance !== this) return
        revoke(); hideOverlay(); clearChallenge()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        Changes.remove(changed)
        instance = null
        Changes.emit()
    }
    internal fun isOverlayVisible() = overlay != null
    internal fun isGranted() = grantedWindow != null
    companion object { @Volatile var instance: GuardService? = null; private set }
}
