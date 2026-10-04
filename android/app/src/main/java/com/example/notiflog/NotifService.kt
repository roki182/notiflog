package com.example.notiflog

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

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
            .put("kind", "notif")
            .put("ts", sbn.postTime)
            .put("app", sbn.packageName)
            .put("title", title)
            .put("text", text)
        Store.append(this, json.toString())
    }
}
