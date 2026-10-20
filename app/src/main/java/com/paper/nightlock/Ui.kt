package com.paper.nightlock

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.ActionMode
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

object Ui {
    val background = Color.rgb(12, 20, 35)
    val card = Color.rgb(23, 36, 55)
    val accent = Color.rgb(115, 226, 206)
    val muted = Color.rgb(163, 181, 200)
    val white = Color.rgb(240, 246, 252)
    val warning = Color.rgb(255, 206, 128)
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun rounded(color: Int, radius: Float = 18f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun column(context: Context, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, padding); setPadding(p, p, p, p)
    }
    fun text(context: Context, value: String, size: Float = 15f, color: Int = white, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(context, 4).toFloat(), 1f)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(context, 5), 0, dp(context, 5))
    }
    fun button(context: Context, value: String, primary: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Ui.background else Ui.white)
        backgroundTintList = ColorStateList.valueOf(if (primary) accent else Color.rgb(39, 56, 78))
        minHeight = dp(context, 48)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8) }
    }
    fun box(parent: LinearLayout, content: LinearLayout.() -> Unit): LinearLayout {
        val box = column(parent.context, 17).apply { background = rounded(card, dp(context, 18).toFloat()); content() }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(parent.context, 14) })
        return box
    }
    fun edgeInsets(view: View, base: Int = 0) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val padding = dp(v.context, base)
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                    padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}

class TypingEditText(context: Context) : EditText(context) {
    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        setTextColor(Ui.white); setHintTextColor(Ui.muted); textSize = 16f
        hint = "在这里手动抄写上方摘要…"
        minLines = 4; maxLines = 8
        filters = arrayOf(InputFilter.LengthFilter(20000))
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setAutofillHints(null)
        val noActions = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        customSelectionActionModeCallback = noActions
        customInsertionActionModeCallback = noActions
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return true
        return super.onTextContextMenuItem(id)
    }
    override fun onDragEvent(event: DragEvent) = false
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun performContextMenuAction(id: Int): Boolean = if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) true else super.performContextMenuAction(id)
        }
    }
}

class ChallengePane(context: Context, val paper: Paper, practice: Boolean, onExit: () -> Unit,
    private val onPassed: () -> Boolean) : LinearLayout(context) {
    val input = TypingEditText(context)
    val result = Ui.text(context, "忽略空白、标点和英文大小写 · ≥90% 通过", 13f, Ui.muted)
    lateinit var submit: Button
        private set
    private var disposed = false
    private val main = Handler(Looper.getMainLooper())
    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.background)
        Ui.edgeInsets(this, 16)
        addView(Ui.text(context, if (practice) "抄写练习 · 不计次数" else "夜间限制中", 13f, Ui.accent, true))
        addView(Ui.text(context, if (practice) "先试着慢下来" else "抄完这一篇，再决定", 25f, bold = true))
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val content = Ui.column(context)
        Ui.box(content) {
            addView(Ui.text(context, paper.title, 18f, bold = true))
            if (paper.id == -1L) addView(Ui.text(context, "此文本仅供演示，不是真实论文摘要", 12f, Ui.warning))
            addView(Ui.text(context, paper.abstractText, 16f))
        }
        content.addView(input, LayoutParams(-1, -2))
        content.addView(result)
        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))
        submit = Ui.button(context, if (practice) "校验练习" else "校验并放行", true) {
            val answer = input.text.toString()
            submit.isEnabled = false; result.text = "正在比对…"
            executor.execute {
                val score = TextScorer.score(paper.abstractText, answer)
                main.post {
                    if (!disposed) {
                        if (score.passed) {
                            if (practice) {
                                result.setTextColor(Ui.accent); result.text = "${score.display} · 练习通过，不计次数，也不解除应用限制"
                                submit.isEnabled = true
                            } else if (!onPassed()) {
                                result.setTextColor(Ui.warning); result.text = "当前验证已失效，请返回后重试"; submit.isEnabled = true
                            }
                        } else {
                            result.setTextColor(Ui.warning)
                            result.text = "${score.display} · 还未达到 90%，请继续核对原文"
                            submit.isEnabled = true
                        }
                    }
                }
            }
        }
        addView(submit)
        addView(Ui.button(context, if (practice) "返回首页" else "返回桌面", false, onExit))
    }
    fun dispose() { disposed = true }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
