package com.tvpptx.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Files sent from a phone plus recently opened ones, newest first. */
object Library {
    class Item(val file: File, val time: Long, val received: Boolean)

    private const val PREFS = "library"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 40

    fun receivedDir(context: Context) = File(context.filesDir, "received").apply { mkdirs() }

    fun recordOpened(context: Context, file: File) {
        val recent = loadRecent(context).filterKeys { it != file.absolutePath }.toMutableMap()
        recent[file.absolutePath] = System.currentTimeMillis()
        saveRecent(context, recent.entries.sortedByDescending { it.value }.take(MAX_RECENT).associate { it.key to it.value })
    }

    fun items(context: Context): List<Item> {
        val dir = receivedDir(context)
        val recent = loadRecent(context)
        val out = HashMap<String, Item>()
        dir.listFiles()?.filter { it.isFile && Documents.isSupported(it.name) }?.forEach { f ->
            out[f.absolutePath] = Item(f, maxOf(f.lastModified(), recent[f.absolutePath] ?: 0L), true)
        }
        for ((path, time) in recent) {
            if (path in out) continue
            val f = File(path)
            if (f.isFile) out[path] = Item(f, time, false)
        }
        return out.values.sortedByDescending { it.time }
    }

    /** Forgets a recent file; files received from a phone are deleted. */
    fun remove(context: Context, item: Item) {
        saveRecent(context, loadRecent(context).filterKeys { it != item.file.absolutePath })
        if (item.received) item.file.delete()
    }

    private fun loadRecent(context: Context): Map<String, Long> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.getString("p") to it.getLong("t") } }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveRecent(context: Context, map: Map<String, Long>) {
        val arr = JSONArray()
        for ((p, t) in map) arr.put(JSONObject().put("p", p).put("t", t))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECENT, arr.toString()).apply()
    }
}
