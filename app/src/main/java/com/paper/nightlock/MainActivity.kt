package com.paper.nightlock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var repository: Repository
    private lateinit var root: LinearLayout
    private var page = "首页"
    private var practicePane: ChallengePane? = null
    private var editing = false
    private val changed: () -> Unit = { if (!editing && practicePane == null && ::root.isInitialized) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); repository = Repository.get(this)
        page = savedInstanceState?.getString("page") ?: "首页"
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        Changes.listen(changed)
        render()
    }
    override fun onResume() {
        super.onResume()
        Scheduler.sync(this); GuardService.instance?.reconcile()
        if (practicePane == null && !editing) render()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onDestroy() { Changes.remove(changed); practicePane?.dispose(); super.onDestroy() }

    private fun render() {
        root = Ui.column(this).apply { setBackgroundColor(Ui.background); Ui.edgeInsets(this) }
        val header = Ui.column(this, 20)
        header.addView(Ui.text(this, "☾  论文夜锁", 24f, bold = true))
        header.addView(Ui.text(this, "给睡眠留一点空间  /  完全离线", 12f, Ui.muted))
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = Ui.column(this, 20)
        when (page) {
            "首页" -> home(content)
            "应用" -> apps(content)
            "论文库" -> library(content)
            else -> settings(content)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0) }
        listOf("首页", "应用", "论文库", "设置").forEach { item ->
            nav.addView(Ui.button(this, item, item == page) { page = item; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun home(content: LinearLayout) {
        val papers = repository.papers()
        val missing = Permissions.missing(this)
        val active = prefs.window.active(ZonedDateTime.now())
        Ui.box(content) {
            addView(Ui.text(context, "NIGHT ROUTINE", 11f, Ui.accent, true))
            addView(Ui.text(context, "把夜晚留给睡眠", 28f, bold = true))
            addView(Ui.text(context, "${NightWindow.label(prefs.start)} — ${NightWindow.label(prefs.end)}", 27f, Ui.accent, true))
            addView(Ui.text(context, "抄写达到 90% 后，放行至下一次锁屏。", 14f, Ui.muted))
            val toggle = Switch(context).apply {
                text = "启用夜间计划"; textSize = 17f; setTextColor(Ui.white); isChecked = prefs.enabled
                setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 8))
                setOnCheckedChangeListener { _, enabled ->
                    val issues = Permissions.missing(context)
                    if (enabled && issues.isNotEmpty()) {
                        isChecked = false
                        AlertDialog.Builder(this@MainActivity).setTitle("还差这几步")
                            .setMessage(issues.joinToString("\n") { "• $it" })
                            .setPositiveButton("去准备") { _, _ -> page = "设置"; render() }.setNegativeButton("稍后", null).show()
                    } else {
                        prefs.enabled = enabled; prefs.lastError = ""; Changes.emit()
                    }
                }
            }
            addView(toggle)
            val status = when {
                !prefs.enabled -> "计划未启用"
                missing.isNotEmpty() -> "未就绪：${missing.joinToString("、")}"
                active == null -> "已就绪 · 等待下一个夜间时段"
                GuardService.instance?.isGranted() == true -> "本次已放行 · 再次锁屏后需重新抄写"
                else -> "夜间限制中 · 打开勾选应用需先抄写"
            }
            addView(Ui.text(context, status, 13f, if (missing.isNotEmpty() && prefs.enabled) Ui.warning else Ui.accent))
            if (prefs.lastError.isNotEmpty()) addView(Ui.text(context, prefs.lastError, 13f, Ui.warning))
        }
        Ui.box(content) {
            addView(Ui.text(context, "你的夜间准备", 18f, bold = true))
            addView(Ui.text(context, "${papers.size} 篇真实摘要   ·   ${prefs.selected.size} 个受限应用", 16f))
            addView(Ui.text(context, "系统授权${if (missing.isEmpty()) "已就绪" else "尚未齐备"}。iQOO 自启动和后台运行需按设置页说明手动确认。", 13f, Ui.muted))
            addView(Ui.button(context, "检查权限与后台设置") { page = "设置"; render() })
        }
        if (papers.any { it.uses >= 3 }) Ui.box(content) {
            addView(Ui.text(context, "该换一批论文了", 18f, Ui.warning, true))
            addView(Ui.text(context, "已有摘要成功使用至少 3 次。你仍可继续使用，也可以更换论文库。", 14f, Ui.muted))
            addView(Ui.button(context, "查看论文库") { page = "论文库"; render() })
        }
        content.addView(Ui.button(this, "先练习一次抄写", true) { practice() })
        content.addView(Ui.text(this, "练习不计次数，不解除夜间限制。系统密码保持原样。", 12f, Ui.muted))
    }

    private fun apps(content: LinearLayout) {
        content.addView(Ui.text(this, "限制哪些应用", 24f, bold = true))
        content.addView(Ui.text(this, "只限制你勾选的应用。电话、桌面、系统设置、输入法和本工具保持可用。", 14f, Ui.muted))
        val excluded = Safety.neverBlocked(this)
        @Suppress("DEPRECATION")
        val activities = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName !in excluded }
            .sortedBy { it.loadLabel(packageManager).toString() }
        if (activities.isEmpty()) content.addView(Ui.text(this, "没有发现可选择的普通应用", 15f, Ui.warning))
        activities.forEach { activity ->
            val pkg = activity.activityInfo.packageName
            val check = CheckBox(this).apply {
                text = "${activity.loadLabel(packageManager)}\n$pkg"; textSize = 14f; setTextColor(Ui.white)
                setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10)); isChecked = pkg in prefs.selected
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.accent)
                setOnCheckedChangeListener { _, checked ->
                    prefs.selected = if (checked) prefs.selected + pkg else prefs.selected - pkg
                    // Avoid rebuilding a long checkbox list on every tap.
                    Scheduler.sync(this@MainActivity); GuardService.instance?.reconcile()
                }
            }
            content.addView(check)
        }
    }

    private fun library(content: LinearLayout) {
        content.addView(Ui.text(this, "让摘要轮流出现", 24f, bold = true))
        content.addView(Ui.text(this, "优先使用次数最少的摘要，同次数随机。使用次数只在夜间成功放行时增加。", 14f, Ui.muted))
        content.addView(Ui.button(this, "＋ 添加真实论文", true) { editPaper(null) })
        content.addView(Ui.button(this, "导入 JSON 论文库") { importLibrary() })
        val papers = repository.papers()
        if (papers.isEmpty()) Ui.box(content) {
            addView(Ui.text(context, "真实论文库还空着", 19f, bold = true))
            addView(Ui.text(context, "把论文题名和经核对的摘要加入这里。演示文本只用于练习，不能启用夜间计划。", 14f, Ui.muted))
        }
        papers.forEach { paper ->
            Ui.box(content) {
                addView(Ui.text(context, if (paper.uses >= 3) "已使用 ${paper.uses} 次 · 建议换库" else "已使用 ${paper.uses} 次", 12f, if (paper.uses >= 3) Ui.warning else Ui.accent))
                addView(Ui.text(context, paper.title, 18f, bold = true))
                addView(Ui.text(context, paper.abstractText.take(160) + if (paper.abstractText.length > 160) "…" else "", 14f, Ui.muted))
                if (paper.sourceUrl.isNotBlank()) addView(Ui.text(context, paper.sourceUrl, 11f, Ui.muted))
                addView(Ui.button(context, "查看 / 编辑") { editPaper(paper) })
                addView(Ui.button(context, "删除") {
                    AlertDialog.Builder(this@MainActivity).setTitle("删除这篇论文？").setMessage(paper.title)
                        .setPositiveButton("删除") { _, _ -> repository.delete(paper.id) }.setNegativeButton("取消", null).show()
                })
            }
        }
        content.addView(Ui.text(this, "整库导入会替换现有论文并清零次数；修改摘要正文只清零该篇次数。", 12f, Ui.muted))
    }

    private fun editPaper(paper: Paper?) {
        editing = true
        val form = Ui.column(this, 18)
        fun field(label: String, value: String, multiline: Boolean = false): EditText {
            form.addView(Ui.text(this, label, 13f, Ui.accent))
            return EditText(this).apply {
                setText(value); setTextColor(Ui.white); textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                if (multiline) { minLines = 5; maxLines = 10 }
                form.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val title = field("论文题名", paper?.title.orEmpty())
        val abstract = field("经核对的原文摘要", paper?.abstractText.orEmpty(), true)
        val source = field("来源链接（可选）", paper?.sourceUrl.orEmpty())
        form.addView(Ui.text(this, "请添加真实摘要。修改正文会重置该篇的使用次数。", 12f, Ui.muted))
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle(if (paper == null) "添加论文" else "编辑论文")
            .setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnDismissListener { editing = false; render() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { repository.save(PaperInput(title.text.toString().trim(), abstract.text.toString().trim(), source.text.toString().trim()), paper?.id) }
                    .onSuccess { dialog.dismiss() }.onFailure { toast(it.message ?: "保存失败") }
            }
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun importLibrary() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, 10) }.onFailure { toast("未找到文件选择器") }
    }
    @Deprecated("Uses the platform document picker without additional dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    require(output.size() + read <= 2_000_000) { "文件不能超过 2 MB" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("无法读取文件")
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            Repository.parseImport(text)
        }.onSuccess { inputs ->
            AlertDialog.Builder(this).setTitle("导入 ${inputs.size} 篇论文？")
                .setMessage("将替换当前论文库并清零使用次数。\n\n${inputs.take(5).joinToString("\n") { it.title }}")
                .setPositiveButton("替换论文库") { _, _ ->
                    runCatching { repository.replace(inputs) }.onSuccess { page = "论文库"; render(); toast("已导入 ${inputs.size} 篇论文") }
                        .onFailure { toast(it.message ?: "导入失败，原论文库保持不变") }
                }.setNegativeButton("取消", null).show()
        }.onFailure { toast("未导入：${it.message ?: "请检查 JSON 格式和 UTF-8 编码"}") }
    }

    private fun settings(content: LinearLayout) {
        content.addView(Ui.text(this, "把准备工作做好", 24f, bold = true))
        Ui.box(content) {
            addView(Ui.text(context, "夜间时段", 18f, bold = true))
            addView(Ui.button(context, "开始：${NightWindow.label(prefs.start)}") { chooseTime(true) })
            addView(Ui.button(context, "结束：${NightWindow.label(prefs.end)}") { chooseTime(false) })
            addView(Ui.text(context, "使用手机本地时间；支持跨午夜。开始时锁屏一次，结束时自动恢复。", 13f, Ui.muted))
        }
        Ui.box(content) {
            addView(Ui.text(context, "必要授权", 18f, bold = true))
            addView(Ui.text(context, "无障碍：${if (Permissions.accessibility(context) && GuardService.instance != null) "已连接" else "未连接"}", 14f, Ui.accent))
            addView(Ui.text(context, "只使用应用切换时的包名，不读取其他应用文字、不自动点击。服务关闭后即停止限制。", 13f, Ui.muted))
            addView(Ui.button(context, "设置无障碍服务") {
                AlertDialog.Builder(this@MainActivity).setTitle("开启应用限制服务")
                    .setMessage("服务会识别前台应用，并在你设定的时段锁屏和展示抄写界面。只监听应用切换包名，不读取页面文字，也不联网。\n\n如系统提示受限，请先进入本工具的应用信息，在右上角菜单允许受限制的设置，再返回开启服务。")
                    .setPositiveButton("前往系统设置") { _, _ ->
                        openSettings(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this@MainActivity, GuardService::class.java)),
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.setNegativeButton("取消", null).show()
            })
            addView(Ui.text(context, "精确定时：${if (Permissions.exact(context)) "已允许" else "未允许"}", 14f, Ui.accent))
            addView(Ui.button(context, "设置闹钟和提醒权限") {
                if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                else toast("当前系统无需单独授权")
            })
            addView(Ui.button(context, "允许状态通知（可选）") {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
                else toast("当前系统无需单独授权")
            })
        }
        Ui.box(content) {
            addView(Ui.text(context, "iQOO 后台运行", 18f, bold = true))
            addView(Ui.text(context, "① 在系统设置搜索“自启动”，允许论文夜锁自启动。\n② 搜索“后台耗电管理”或应用电池设置，允许本工具后台运行。\n③ 如有“后台弹出界面”限制，允许本工具显示验证界面。\n④ 最近任务中可将本工具锁定，减少被一键清理。\n\n菜单名称随 OriginOS 版本变化。应用无法自动确认这些厂商开关，请按实际界面检查。", 14f, Ui.muted))
            val ignored = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            addView(Ui.text(context, "系统电池优化：${if (ignored) "已豁免" else "尚未豁免 / 由系统管理"}", 13f, Ui.accent))
            addView(Ui.button(context, "打开应用信息") { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
            addView(Ui.button(context, "打开电池优化设置") { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        }
        Ui.box(content) {
            addView(Ui.text(context, "数据与约束强度", 18f, bold = true))
            addView(Ui.text(context, "摘要、设置和使用次数只保存在本机。应用未申请联网权限。\n\n这是个人自律工具：系统设置保持可用，关闭授权或卸载即可停止。系统密码不会被修改。\n\n版本 1.0.0 · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}", 13f, Ui.muted))
        }
    }

    private fun chooseTime(start: Boolean) {
        val minute = if (start) prefs.start else prefs.end
        TimePickerDialog(this, { _, hour, min ->
            val value = hour * 60 + min
            if (value == if (start) prefs.end else prefs.start) toast("开始和结束时间不能相同")
            else { if (start) prefs.start = value else prefs.end = value; Changes.emit() }
        }, minute / 60, minute % 60, true).show()
    }
    private fun practice() {
        val pane = ChallengePane(this, repository.pick() ?: Repository.demo, true,
            onExit = { practicePane?.dispose(); practicePane = null; render() }, onPassed = { true })
        practicePane = pane
        setContentView(pane)
        pane.requestApplyInsets()
    }
    private fun openSettings(intent: Intent, fallback: Intent = Intent(Settings.ACTION_SETTINGS)) {
        runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(fallback) }.onFailure { toast("请手动打开系统设置") } }
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    // Android 13+ uses the dispatcher above; this override is only the API 29-32 fallback.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy Android 10-12 back handler")
    override fun onBackPressed() {
        handleBack()
    }
    private fun handleBack() {
        if (practicePane != null) { practicePane?.dispose(); practicePane = null; render() }
        else if (page != "首页") { page = "首页"; render() } else finish()
    }
}
