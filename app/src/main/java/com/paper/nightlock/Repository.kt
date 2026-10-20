package com.paper.nightlock

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Paper(val id: Long, val title: String, val abstractText: String, val sourceUrl: String = "", val uses: Int = 0)
data class PaperInput(val title: String, val abstractText: String, val sourceUrl: String = "")

object Changes {
    private val listeners = linkedSetOf<() -> Unit>()
    fun listen(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun emit() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { emit() }; return
        }
        listeners.toList().forEach { it() }
    }
}

class NightLockApplication : Application() {
    override fun onCreate() { super.onCreate(); Notices.setup(this) }
}

class Prefs(context: Context) {
    private val data = context.getSharedPreferences("night_lock", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = data.getBoolean("enabled", false)
        set(value) { data.edit().putBoolean("enabled", value).commit() }
    var start: Int
        get() = data.getInt("start", 60)
        set(value) { data.edit().putInt("start", value).commit() }
    var end: Int
        get() = data.getInt("end", 420)
        set(value) { data.edit().putInt("end", value).commit() }
    var selected: Set<String>
        get() = data.getStringSet("selected", emptySet())!!.toSet()
        set(value) { data.edit().putStringSet("selected", value.toSet()).commit() }
    var lastLockedWindow: String
        get() = data.getString("last_lock", "")!!
        set(value) { data.edit().putString("last_lock", value).commit() }
    var lastError: String
        get() = data.getString("last_error", "")!!
        set(value) { data.edit().putString("last_error", value).commit() }
    val window: NightWindow get() = NightWindow(start, end)
}

class Repository private constructor(context: Context) : SQLiteOpenHelper(context, "papers.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE papers (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, abstract TEXT NOT NULL, source TEXT NOT NULL, uses INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE completions (token TEXT PRIMARY KEY, paper_id INTEGER NOT NULL, completed_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun papers(): List<Paper> = readableDatabase.rawQuery("SELECT id,title,abstract,source,uses FROM papers ORDER BY uses,id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Paper(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getInt(4))) }
    }
    fun pick(): Paper? {
        val papers = papers()
        return papers.firstOrNull()?.let { first -> papers.filter { it.uses == first.uses }.random() }
    }
    fun save(input: PaperInput, id: Long? = null) {
        validate(input)
        val values = values(input)
        if (id == null) writableDatabase.insertOrThrow("papers", null, values)
        else {
            val previous = papers().find { it.id == id } ?: error("论文已被删除")
            if (previous.abstractText != input.abstractText) values.put("uses", 0)
            writableDatabase.update("papers", values, "id=?", arrayOf(id.toString()))
        }
        Changes.emit()
    }
    fun delete(id: Long) { writableDatabase.delete("papers", "id=?", arrayOf(id.toString())); Changes.emit() }
    fun replace(inputs: List<PaperInput>) {
        require(inputs.isNotEmpty()) { "论文库不能为空" }
        inputs.forEach(::validate)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("papers", null, null); db.delete("completions", null, null)
            inputs.forEach { db.insertOrThrow("papers", null, values(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        Changes.emit()
    }
    /** Returns null if this completion has already been counted or its paper was removed. */
    fun complete(token: String, paper: Paper): Int? {
        val db = writableDatabase
        var result: Int? = null
        db.beginTransaction()
        try {
            val current = papers().find { it.id == paper.id && it.abstractText == paper.abstractText }
            if (current != null) {
                val values = ContentValues().apply { put("token", token); put("paper_id", paper.id); put("completed_at", System.currentTimeMillis()) }
                if (db.insertWithOnConflict("completions", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    db.execSQL("UPDATE papers SET uses=uses+1 WHERE id=?", arrayOf(paper.id))
                    result = current.uses + 1
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (result != null) Changes.emit()
        return result
    }
    private fun values(input: PaperInput) = ContentValues().apply {
        put("title", input.title.trim()); put("abstract", input.abstractText.trim()); put("source", input.sourceUrl.trim())
    }
    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }
        fun validate(input: PaperInput) {
            require(input.title.isNotBlank() && input.title.length <= 300) { "题名不能为空，且不能超过 300 字" }
            require(input.abstractText.isNotBlank() && input.abstractText.length <= 20000 && TextScorer.normalized(input.abstractText).isNotEmpty()) {
                "摘要需包含有效文字，且不能超过 20,000 字符"
            }
        }
        fun parseImport(text: String): List<PaperInput> {
            require(text.length <= 2_000_000) { "文件过大，请使用 2 MB 以内的论文库" }
            val array = JSONArray(text.removePrefix("\uFEFF"))
            require(array.length() in 1..500) { "请导入包含 1—500 篇论文的 JSON 数组" }
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                fun string(key: String): String = (item.get(key) as? String) ?: error("第 ${index + 1} 篇的 $key 必须是文字")
                PaperInput(string("title"), string("abstract"), if (item.has("sourceUrl") && !item.isNull("sourceUrl")) string("sourceUrl") else "")
                    .also(::validate)
            }
        }
        fun token(): String = UUID.randomUUID().toString()
        val demo = Paper(-1, "演示文本 · 非真实论文摘要", "这是一段用于体验抄写流程的演示文本，不属于任何真实论文。夜间减少手机使用，有助于给睡眠留出稳定的时间。本工具通过展示原文、手动输入和文字比对，增加继续使用手机之前的思考机会。练习模式不会增加论文的使用次数，也不会解除夜间应用限制。")
    }
}
