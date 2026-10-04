package com.example.notiflog

import android.content.Context
import android.content.SharedPreferences
import java.io.File

// Antrian lokal: setiap notifikasi/lokasi ditulis dulu ke file di HP (satu JSON per baris).
// Dengan begini data tidak hilang saat internet mati.
object Store {
    private const val QUEUE_FILE = "queue.jsonl"
    private val lock = Any()

    fun append(ctx: Context, line: String) = synchronized(lock) {
        File(ctx.filesDir, QUEUE_FILE).appendText(line + "\n")
    }

    fun readAll(ctx: Context): List<String> = synchronized(lock) {
        val f = File(ctx.filesDir, QUEUE_FILE)
        if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    // Hapus n baris pertama setelah berhasil terkirim.
    fun dropFirst(ctx: Context, n: Int) = synchronized(lock) {
        val f = File(ctx.filesDir, QUEUE_FILE)
        if (f.exists()) {
            val rest = f.readLines().filter { it.isNotBlank() }.drop(n)
            f.writeText(rest.joinToString("") { it + "\n" })
        }
    }
}

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun url(ctx: Context) = sp(ctx).getString("url", "") ?: ""
    fun token(ctx: Context) = sp(ctx).getString("token", "") ?: ""
    fun intervalMin(ctx: Context) = sp(ctx).getInt("interval", 30)
    fun lastLoc(ctx: Context) = sp(ctx).getLong("lastLoc", 0L)

    fun save(ctx: Context, url: String, token: String, interval: Int) {
        sp(ctx).edit()
            .putString("url", url)
            .putString("token", token)
            .putInt("interval", interval)
            .apply()
    }

    fun setLastLoc(ctx: Context, ms: Long) = sp(ctx).edit().putLong("lastLoc", ms).apply()
}
