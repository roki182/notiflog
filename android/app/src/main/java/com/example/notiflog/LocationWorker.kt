package com.example.notiflog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// WorkManager membangunkan worker ini tiap 15 menit (batas minimum Android).
// Worker hanya mengambil lokasi kalau sudah lewat interval yang kamu atur (default 30 menit).
// Di antara itu GPS tidak dinyalakan, jadi hemat baterai.
class LocationWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val now = System.currentTimeMillis()
        val intervalMs = Prefs.intervalMin(ctx) * 60_000L
        if (now - Prefs.lastLoc(ctx) < intervalMs - 60_000L) return Result.success()

        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.success()

        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(60_000L)
            .build()
        val cts = CancellationTokenSource()

        return try {
            @Suppress("MissingPermission")
            val loc = Tasks.await(fused.getCurrentLocation(request, cts.token), 45, TimeUnit.SECONDS)
            if (loc != null) {
                val json = JSONObject()
                    .put("kind", "loc")
                    .put("ts", loc.time)
                    .put("lat", loc.latitude)
                    .put("lon", loc.longitude)
                    .put("acc", loc.accuracy.toDouble())
                Store.append(ctx, json.toString())
                Prefs.setLastLoc(ctx, now)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
