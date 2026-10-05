package com.example.notiflog

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import android.service.notification.StatusBarNotification
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Dijalankan Android setelah kamu mengaktifkan "Akses notifikasi" untuk NotifLog.
class NotifService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        Prefs.setStat(this, "connected")
    }

    // Sistem/pabrikan HP kadang memutus listener (hemat baterai). Minta disambung lagi.
    override fun onListenerDisconnected() {
        connected = false
        Prefs.setStat(this, "disconnected")
        requestRebind(ComponentName(this, NotifService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // jangan catat notifikasi dari app ini sendiri

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.bigText")?.toString()
            ?: extras.getCharSequence("android.text")?.toString()
            ?: ""

        // Aturan trigger dicek sebelum daftar abaikan, jadi app yang tidak dicatat tetap bisa memicu lokasi.
        if (Triggers.matches(this, sbn.packageName, title, text)) scheduleLocationNow()
        if (sbn.packageName in Prefs.ignored(this)) return // app yang kamu pilih untuk diabaikan
        if (sbn.isOngoing && Prefs.skipOngoing(this)) return // notifikasi yang menempel terus di status bar

        val json = JSONObject()
            .put("id", "n|${sbn.key}|${sbn.postTime}") // ID unik untuk mencegah data ganda di sheet
            .put("kind", "notif")
            .put("ts", sbn.postTime)
            .put("app", sbn.packageName)
            .put("title", title)
            .put("text", text)
        Prefs.setStat(this, "notif")
        Store.append(this, json.toString())
        scheduleUploadSoon()
    }

    // Kirim ke sheet 2 detik setelah notifikasi pertama. Notifikasi lain yang masuk dalam 2 detik
    // ikut dalam pengiriman yang sama (KEEP), jadi tidak membuat banyak pekerjaan.
    private fun scheduleUploadSoon() {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("upload-soon", ExistingWorkPolicy.KEEP, req)
    }

    // Ambil lokasi sekarang juga, tanpa menunggu interval. KEEP supaya beberapa notifikasi yang
    // cocok berurutan hanya memicu satu pengambilan.
    private fun scheduleLocationNow() {
        val req = OneTimeWorkRequestBuilder<LocationWorker>()
            .setInputData(workDataOf(LocationWorker.FORCE to true))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("location-trigger", ExistingWorkPolicy.KEEP, req)
    }

    companion object {
        @Volatile
        private var connected = false

        // Dipanggil dari worker berkala. Kalau izin akses notifikasi masih aktif tapi listener
        // tidak tersambung, matikan lalu nyalakan lagi komponennya supaya sistem menyambungkannya.
        fun ensureBound(ctx: Context) {
            if (connected) return
            if (ctx.packageName !in NotificationManagerCompat.getEnabledListenerPackages(ctx)) return
            val cn = ComponentName(ctx, NotifService::class.java)
            val pm = ctx.packageManager
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
