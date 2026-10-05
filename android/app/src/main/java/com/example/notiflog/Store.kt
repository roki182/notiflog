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
            // Tulis ke file sementara lalu ganti nama, supaya antrian tidak kosong/terpotong
            // kalau proses dimatikan di tengah penulisan.
            val tmp = File(ctx.filesDir, "$QUEUE_FILE.tmp")
            tmp.writeText(rest.joinToString("") { it + "\n" })
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
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

    // Catatan waktu kejadian penting (listener tersambung/putus, upload sukses) untuk diagnosa.
    fun stat(ctx: Context, key: String) = sp(ctx).getLong("stat_$key", 0L)

    fun setStat(ctx: Context, key: String) =
        sp(ctx).edit().putLong("stat_$key", System.currentTimeMillis()).apply()

    fun setLastLoc(ctx: Context, ms: Long) = sp(ctx).edit().putLong("lastLoc", ms).apply()

    // Daftar package yang tidak dicatat notifikasinya.
    fun ignored(ctx: Context): Set<String> = sp(ctx).getStringSet("ignored", emptySet()) ?: emptySet()

    fun saveIgnored(ctx: Context, packages: Set<String>) {
        sp(ctx).edit().putStringSet("ignored", packages).apply()
    }

    // Aturan trigger lokasi, satu aturan per baris: aplikasi|judul|isi
    fun rules(ctx: Context) = sp(ctx).getString("rules", "") ?: ""

    fun saveRules(ctx: Context, rules: String) {
        sp(ctx).edit().putString("rules", rules).apply()
    }
}

// Notifikasi yang cocok dengan salah satu aturan memicu pengambilan lokasi seketika.
object Triggers {
    // Bagian aturan yang kosong berarti "apa saja"; aturan yang semuanya kosong diabaikan.
    // Pencocokan "mengandung", tanpa membedakan huruf besar/kecil. Bagian aplikasi dicocokkan
    // ke nama package maupun nama app.
    fun matches(ctx: Context, pkg: String, title: String, text: String): Boolean {
        val rules = Prefs.rules(ctx).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (rules.isEmpty()) return false

        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            ""
        }

        return rules.any { line ->
            val parts = line.split("|", limit = 3).map { it.trim().lowercase() }
            val app = parts.getOrElse(0) { "" }
            val t = parts.getOrElse(1) { "" }
            val x = parts.getOrElse(2) { "" }
            if (app.isEmpty() && t.isEmpty() && x.isEmpty()) return@any false
            (app.isEmpty() || pkg.lowercase().contains(app) || label.lowercase().contains(app)) &&
                (t.isEmpty() || title.lowercase().contains(t)) &&
                (x.isEmpty() || text.lowercase().contains(x))
        }
    }
}
