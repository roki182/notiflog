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
        val url = Prefs.url(ctx)
        val token = Prefs.token(ctx)
        if (url.isBlank() || token.isBlank()) return Result.success()

        val lines = Store.readAll(ctx)
        if (lines.isEmpty()) return Result.success()

        val batch = lines.take(200)
        val items = JSONArray()
        batch.forEach { items.put(JSONObject(it)) }
        val body = JSONObject().put("token", token).put("items", items).toString()

        return try {
            if (post(url, body)) {
                Store.dropFirst(ctx, batch.size)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    // Apps Script membalas dengan redirect 302. Redirect itu harus diikuti dengan POST lagi,
    // bukan GET (HttpURLConnection otomatis mengubahnya jadi GET), makanya redirect diikuti manual.
    private fun post(start: String, body: String): Boolean {
        var url = start
        for (i in 0 until 4) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && location != null) {
                url = location
                continue
            }
            return code in 200..299
        }
        return false
    }
}
