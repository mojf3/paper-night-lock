package com.paper.nightlock

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import java.time.ZonedDateTime

class TestTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "夜锁自动化测试目标应用"; textSize = 24f })
    }
}

/** Dependency-free instrumentation runner. Run only on a fresh emulator installation. */
class SmokeInstrumentation : Instrumentation() {
    private val messages = mutableListOf<String>()
    private var phase = "full"
    private fun checkThat(value: Boolean, message: String) { check(value) { message }; messages += "PASS $message" }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun shell(command: String) = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
    }
    private fun waitFor(message: String, timeout: Long = 10000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end && !predicate()) Thread.sleep(100)
        checkThat(predicate(), message)
    }
    private fun find(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), text)?.let { return it }
        return null
    }
    private fun pane(): ChallengePane? = GuardService.instance?.let { service ->
        GuardService::class.java.getDeclaredField("overlay").apply { isAccessible = true }.get(service) as? ChallengePane
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "full"; start() }
    override fun onStart() {
        if (phase != "full") { recoveryPhase(); return }
        val prefs = Prefs(targetContext)
        val repo = Repository.get(targetContext)
        val results = Bundle()
        var safeToClean = false
        var resultCode = Activity.RESULT_CANCELED
        try {
            checkThat(repo.papers().isEmpty(), "fresh emulator database required")
            safeToClean = true
            main {
                repo.replace(listOf(PaperInput("自动化测试论文", "abcdefghij", "https://example.com/test")))
                val paper = repo.papers().single()
                checkThat(repo.complete("one", paper) == 1, "first completion counted")
                checkThat(repo.complete("one", paper) == null, "duplicate completion ignored")
                checkThat(repo.complete("two", paper) == 2, "second completion counted")
                checkThat(repo.complete("three", paper) == 3, "third completion triggers library reminder")
                repo.save(PaperInput("只修改题名", paper.abstractText), paper.id)
                checkThat(repo.papers().single().uses == 3, "title edit preserves count")
                repo.save(PaperInput("测试论文", "abcdefghijklmno"), paper.id)
                checkThat(repo.papers().single().uses == 0, "abstract edit resets count")
                val before = repo.papers()
                checkThat(runCatching { Repository.parseImport("[{\"title\":\"missing abstract\"}]") }.isFailure, "invalid import rejected")
                checkThat(repo.papers() == before, "invalid import keeps existing library")
                val imported = Repository.parseImport("\uFEFF[{\"title\":\"中文测试\",\"abstract\":\"这是核对过的测试摘要\"}]")
                checkThat(imported.single().sourceUrl == "", "UTF-8 BOM and optional source supported")
                val input = TypingEditText(targetContext)
                input.setText("手动输入")
                targetContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", "粘贴内容"))
                input.onTextContextMenuItem(android.R.id.paste)
                checkThat(input.text.toString() == "手动输入", "paste menu does not insert clipboard")
                checkThat(targetContext.packageName in Safety.neverBlocked(targetContext), "own app always allowed")
                checkThat("com.android.settings" in Safety.neverBlocked(targetContext), "system settings always allowed")
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            main {
                find(activity.window.decorView, "先练习一次抄写")!!.performClick()
                val pane = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ChallengePane
                pane.input.setText(pane.paper.abstractText)
                pane.submit.performClick()
            }
            waitFor("practice UI reports success without counting") {
                main {
                    val pane = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ChallengePane
                    pane.result.text.contains("练习通过") && repo.papers().single().uses == 0
                }
            }
            main { find(activity.window.decorView, "返回首页")!!.performClick() }
            shell("appops set ${targetContext.packageName} SCHEDULE_EXACT_ALARM allow")
            // Instrumentation startup force-stops the target and can mark its previous binding as crashed.
            shell("settings delete secure enabled_accessibility_services")
            Thread.sleep(200)
            shell("settings put secure enabled_accessibility_services ${targetContext.packageName}/.GuardService")
            shell("settings put secure accessibility_enabled 1")
            waitFor("accessibility service connected") { GuardService.instance != null }
            val minute = ZonedDateTime.now().let { it.hour * 60 + it.minute }
            main {
                prefs.start = (minute + 1439) % 1440; prefs.end = (minute + 3) % 1440
                prefs.selected = setOf(context.packageName)
                prefs.lastLockedWindow = prefs.window.active(ZonedDateTime.now())!!.key
                prefs.enabled = true
                Changes.emit()
            }
            fun launchTarget() {
                // Open from Home, matching a user launch and ensuring a genuine foreground transition.
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(300)
                shell("am start -n ${context.packageName}/${TestTargetActivity::class.java.name}")
            }
            launchTarget()
            waitFor("selected application shows typing overlay") { main { pane() != null } }
            main { pane()!!.input.setText("wrong"); pane()!!.submit.performClick() }
            waitFor("wrong answer keeps application blocked") { main { pane()?.result?.text?.contains("还未达到") == true } }
            checkThat(repo.papers().single().uses == 0, "failed answer does not increment count")
            main { pane()!!.input.setText(pane()!!.paper.abstractText); pane()!!.submit.performClick() }
            waitFor("correct answer grants until screen off") { main { GuardService.instance?.isGranted() == true && pane() == null } }
            checkThat(repo.papers().single().uses == 1, "successful overlay increments exactly once")
            shell("input keyevent KEYCODE_SLEEP")
            waitFor("screen off revokes grant") { main { GuardService.instance?.isGranted() == false } }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            launchTarget()
            waitFor("unlocking again requires another abstract") { main { pane() != null } }
            shell("settings delete secure enabled_accessibility_services")
            waitFor("revoking accessibility closes overlay and disconnects") { GuardService.instance == null }
            shell("settings put secure enabled_accessibility_services ${targetContext.packageName}/.GuardService")
            shell("settings put secure accessibility_enabled 1")
            waitFor("accessibility reconnects without retaining grant") { main { GuardService.instance != null && GuardService.instance?.isGranted() == false } }
            launchTarget()
            waitFor("reconnected service requires a fresh verification") { main { pane() != null } }
            main { find(pane()!!, "返回桌面")!!.performClick() }
            waitFor("home escape closes overlay") { main { pane() == null } }
            main { targetContext.startActivity(Intent(SettingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Thread.sleep(500)
            checkThat(main { pane() == null }, "unselected system settings remains usable")
            launchTarget()
            waitFor("blocking resumes when selected application opens") { main { pane() != null } }
            main {
                prefs.start = (minute + 1438) % 1440; prefs.end = (minute + 1439) % 1440
                Changes.emit()
            }
            waitFor("end of window dismisses overlay automatically") { main { pane() == null && GuardService.instance?.isGranted() == false } }
            main {
                prefs.start = (minute + 1439) % 1440; prefs.end = (minute + 3) % 1440
                prefs.lastLockedWindow = ""; Changes.emit()
            }
            waitFor("new window executes system screen lock") { !targetContext.getSystemService(PowerManager::class.java).isInteractive }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            launchTarget()
            waitFor("screen lock is not repeated during typing") { main { pane() != null } }
            main { prefs.enabled = false; Changes.emit() }
            waitFor("turning plan off dismisses overlay") { main { pane() == null } }
            results.putString("stream", messages.joinToString("\n", postfix = "\nALL SMOKE TESTS PASSED\n"))
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            results.putString("stream", messages.joinToString("\n") + "\nFAIL ${error.stackTraceToString()}\n")
        } finally {
            if (safeToClean) main {
                prefs.enabled = false; prefs.start = 60; prefs.end = 420; prefs.selected = emptySet(); prefs.lastLockedWindow = ""; prefs.lastError = ""
                repo.papers().forEach { repo.delete(it.id) }
                Changes.emit()
            }
            if (safeToClean) shell("settings delete secure enabled_accessibility_services")
        }
        finish(resultCode, results)
    }
    private fun recoveryPhase() {
        val prefs = Prefs(targetContext)
        val repo = Repository.get(targetContext)
        val results = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        try {
            when (phase) {
                "cleanup" -> {
                    main {
                        prefs.enabled = false; prefs.start = 60; prefs.end = 420; prefs.selected = emptySet()
                        prefs.lastLockedWindow = ""; prefs.lastError = ""
                        repo.papers().forEach { repo.delete(it.id) }; Changes.emit()
                    }
                    shell("settings delete secure enabled_accessibility_services")
                    messages += "PASS emulator test state cleaned"
                }
                "recovery-setup" -> {
                    checkThat(repo.papers().isEmpty(), "fresh recovery database required")
                    main { repo.save(PaperInput("自动化恢复测试专用", "Recovery test abstract for verifying offline persistence and screen lock grants.")) }
                    shell("appops set ${targetContext.packageName} SCHEDULE_EXACT_ALARM allow")
                    shell("settings delete secure enabled_accessibility_services"); Thread.sleep(200)
                    shell("settings put secure enabled_accessibility_services ${targetContext.packageName}/.GuardService")
                    shell("settings put secure accessibility_enabled 1")
                    waitFor("recovery service connected") { GuardService.instance != null }
                    main {
                        val minute = ZonedDateTime.now().let { it.hour * 60 + it.minute }
                        prefs.start = (minute + 1439) % 1440; prefs.end = (minute + 30) % 1440
                        prefs.selected = setOf(context.packageName)
                        prefs.lastLockedWindow = prefs.window.active(ZonedDateTime.now())!!.key
                        prefs.enabled = true; Changes.emit()
                    }
                    shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                    shell("input keyevent KEYCODE_HOME"); Thread.sleep(300)
                    shell("am start -n ${context.packageName}/${TestTargetActivity::class.java.name}")
                    waitFor("recovery overlay displayed") { main { pane() != null } }
                    main { pane()!!.input.setText(pane()!!.paper.abstractText); pane()!!.submit.performClick() }
                    waitFor("recovery grant obtained before reboot") { main { GuardService.instance?.isGranted() == true } }
                    checkThat(repo.papers().single().uses == 1, "recovery count persisted before reboot")
                }
                "post-revoke-check" -> {
                    checkThat(prefs.enabled, "user plan remains configured after permission revocation")
                    checkThat(repo.papers().single().uses == 1, "paper and successful count survived device reboot")
                    checkThat(!Permissions.exact(targetContext), "revoked exact alarm permission detected after process restart")
                    checkThat(Permissions.missing(targetContext).any { it.contains("精确定时") }, "missing permission reported as not ready")
                    checkThat(GuardService.instance?.isGranted() != true, "no grant survives process termination")
                    startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                else -> error("Unknown test phase: $phase")
            }
            resultCode = Activity.RESULT_OK
            results.putString("stream", messages.joinToString("\n", postfix = "\nPHASE $phase PASSED\n"))
        } catch (error: Throwable) { results.putString("stream", messages.joinToString("\n") + "\nFAIL ${error.stackTraceToString()}\n") }
        finish(resultCode, results)
    }
    companion object { private const val SettingsAction = "android.settings.SETTINGS" }
}
