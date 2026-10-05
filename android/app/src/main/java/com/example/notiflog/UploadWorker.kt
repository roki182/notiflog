package com.example.notiflog

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Mengirim antrian ke Apps Script secara berkala, hanya saat ada internet.
class UploadWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        // Worker ini jalan tiap 15 menit, jadi sekalian pastikan listener notifikasi masih tersambung.
        NotifService.ensureBound(ctx)
        Prefs.setStat(ctx, "worker")
        // Hanya satu upload yang boleh berjalan. Kalau dua worker jalan bersamaan, keduanya membaca
        // antrian yang sama dan mengirim data yang sama, sehingga muncul baris ganda di sheet.
        return synchronized(UPLOAD_LOCK) { upload() }
    }

    private fun upload(): Result {
        val url = Prefs.url(ctx)
        val token = Prefs.token(ctx)
        if (url.isBlank() || token.isBlank()) return Result.success()

        val ignored = Prefs.ignored(ctx)

        // Kirim antrian per 200 baris sampai habis (maksimal 25 putaran per eksekusi).
        repeat(25) {
            val lines = Store.readAll(ctx)
            if (lines.isEmpty()) return Result.success()

            val batch = lines.take(200)
            val items = JSONArray()
            // Baris rusak (misalnya terpotong saat app dimatikan paksa) dilewati, bukan membuat
            // seluruh antrian macet selamanya. Notifikasi dari app yang kini diabaikan juga dibuang,
            // termasuk yang sudah telanjur masuk antrian. Baris itu ikut dibuang bersama batch.
            batch.forEach { line ->
                try {
                    val obj = JSONObject(line)
                    if (obj.optString("kind") == "notif" && obj.optString("app") in ignored) return@forEach
                    items.put(obj)
                } catch (e: Exception) {
                }
            }
            if (items.length() > 0) {
                val body = JSONObject().put("token", token).put("items", items).toString()
                try {
                    if (!post(url, body)) return Result.retry()
                } catch (e: Exception) {
                    return Result.retry()
                }
                Prefs.setStat(ctx, "upload")
            }
            Store.dropFirst(ctx, batch.size)
        }
        return Result.success()
    }

    private companion object {
        val UPLOAD_LOCK = Any()
    }

    // Apps Script menjalankan doPost pada permintaan pertama, lalu membalas dengan redirect 302 ke
    // alamat tempat hasilnya diambil. Redirect itu harus diikuti dengan GET (bukan POST, yang ditolak).
    private fun post(start: String, body: String): Boolean {
        var url = start
        for (i in 0 until 4) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (i == 0) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                c.requestMethod = "GET"
            }
            val code = c.responseCode
            val location = c.getHeaderField("Location")
            // Apps Script membalas 200 walau token salah ("forbidden"), jadi isi balasan harus dicek.
            val reply = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
            c.disconnect()
            if (code in 300..399 && location != null) {
                url = location
                continue
            }
            return code in 200..299 && reply.trim() == "ok"
        }
        return false
    }
}
