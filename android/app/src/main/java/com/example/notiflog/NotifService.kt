package com.example.notiflog

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Dijalankan Android setelah kamu mengaktifkan "Akses notifikasi" untuk NotifLog.
class NotifService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // jangan catat notifikasi dari app ini sendiri
        if (sbn.packageName in Prefs.ignored(this)) return // app yang kamu pilih untuk diabaikan

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.bigText")?.toString()
            ?: extras.getCharSequence("android.text")?.toString()
            ?: ""

        val json = JSONObject()
            .put("id", "n|${sbn.key}|${sbn.postTime}") // ID unik untuk mencegah data ganda di sheet
            .put("kind", "notif")
            .put("ts", sbn.postTime)
            .put("app", sbn.packageName)
            .put("title", title)
            .put("text", text)
        Store.append(this, json.toString())
        scheduleUploadSoon()
    }

    // Kirim ke sheet 20 detik setelah notifikasi pertama. Notifikasi lain yang masuk dalam 20 detik
    // ikut dalam pengiriman yang sama (KEEP), jadi tidak membuat banyak pekerjaan.
    private fun scheduleUploadSoon() {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("upload-soon", ExistingWorkPolicy.KEEP, req)
    }
}
