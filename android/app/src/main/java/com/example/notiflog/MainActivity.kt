package com.example.notiflog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etInterval: EditText
    private lateinit var tvStatus: TextView
    private lateinit var etRules: EditText

    private val locPermRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        etUrl = EditText(this).apply { hint = "URL Web App Apps Script" }
        etToken = EditText(this).apply { hint = "Token (sama dengan TOKEN di Apps Script)" }
        etInterval = EditText(this).apply {
            hint = "Interval lokasi (menit), default 30"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val btnNotif = Button(this).apply {
            text = "1. Buka Akses Notifikasi"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        val btnLoc = Button(this).apply {
            text = "2. Izinkan Lokasi"
            setOnClickListener {
                locPermRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
        val btnBgLoc = Button(this).apply {
            text = "3. Buka Pengaturan App (pilih Lokasi > Izinkan sepanjang waktu)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
        val btnBattery = Button(this).apply {
            text = "Matikan Optimasi Baterai (agar tidak dimatikan sistem)"
            setOnClickListener {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this@MainActivity, "Sudah dikecualikan dari optimasi baterai", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        val btnIgnore = Button(this).apply {
            text = "Pilih App yang Diabaikan"
            setOnClickListener { pickIgnoredApps() }
        }
        val btnSave = Button(this).apply {
            text = "4. Simpan & Aktifkan"
            setOnClickListener { saveAndSchedule() }
        }

        root.addView(TextView(this).apply { text = "NotifLog"; textSize = 20f })
        root.addView(etUrl)
        root.addView(etToken)
        root.addView(etInterval)
        root.addView(btnNotif)
        root.addView(btnLoc)
        root.addView(btnBgLoc)
        etRules = EditText(this).apply {
            hint = "Trigger lokasi, satu aturan per baris: aplikasi|judul|isi\n" +
                "(kosongkan bagian yang bebas, contoh: whatsapp||darurat)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        root.addView(etRules)
        tvStatus = TextView(this)
        root.addView(tvStatus)
        root.addView(android.widget.CheckBox(this).apply {
            text = "Abaikan notifikasi ongoing (speed meter, musik, navigasi)"
            isChecked = Prefs.skipOngoing(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.saveSkipOngoing(this@MainActivity, checked) }
        })
        root.addView(btnBattery)
        root.addView(btnIgnore)
        root.addView(btnSave)
        setContentView(root)

        etUrl.setText(Prefs.url(this))
        etToken.setText(Prefs.token(this))
        etInterval.setText(Prefs.intervalMin(this).toString())
        etRules.setText(Prefs.rules(this))
    }

    override fun onResume() {
        super.onResume()
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US)
        fun t(key: String): String {
            val ms = Prefs.stat(this, key)
            return if (ms == 0L) "-" else fmt.format(java.util.Date(ms))
        }
        tvStatus.text = "Listener tersambung: ${t("connected")}\n" +
            "Listener terputus: ${t("disconnected")}\n" +
            "Notifikasi terakhir: ${t("notif")}\n" +
            "Worker terakhir jalan: ${t("worker")}\n" +
            "Upload sukses terakhir: ${t("upload")}\n" +
            "Antrian belum terkirim: ${Store.readAll(this).size}\n" +
            "App diabaikan: ${Prefs.ignored(this).sorted().joinToString(", ").ifEmpty { "-" }}\n" +
            "Notifikasi terbaru (status):\n" + recentNotifs() + "\n" +
            "Proses terakhir dimatikan:\n" + exitReasons(fmt)
    }

    // 8 notifikasi terakhir yang diterima app, yang terbaru di atas, beserta statusnya.
    private fun recentNotifs(): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val rows = Prefs.recent(this).takeLast(8).reversed().mapNotNull {
            val p = it.split("|", limit = 4)
            if (p.size < 4) null
            else "${fmt.format(java.util.Date(p[0].toLongOrNull() ?: 0L))} [${p[1]}] ${p[2]} - ${p[3]}"
        }
        return if (rows.isEmpty()) "-" else rows.joinToString("\n")
    }

    // Android mencatat kenapa proses app dimatikan (Android 11 ke atas). Tampilkan 5 terakhir.
    private fun exitReasons(fmt: java.text.SimpleDateFormat): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return "(perlu Android 11+)"
        return try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            if (list.isEmpty()) "-" else list.joinToString("\n") {
                "${fmt.format(java.util.Date(it.timestamp))}  ${reasonName(it.reason)}" +
                    (it.description?.let { d -> " ($d)" } ?: "")
            }
        } catch (e: Exception) {
            "-"
        }
    }

    private fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED (dibunuh sinyal)"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED (force stop)"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "reason $r"
    }

    // Tampilkan semua app yang punya ikon peluncur, lalu simpan pilihan centang sebagai daftar abaikan.
    private fun pickIgnoredApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val current = Prefs.ignored(this)
        // Nama package ikut ditampilkan, karena ada app yang namanya mirip.
        val labels: Array<CharSequence> = apps.map { "${it.second}\n${it.first}" as CharSequence }.toTypedArray()
        val checked = apps.map { it.first in current }.toBooleanArray()
        val selected = checked.copyOf()

        android.app.AlertDialog.Builder(this)
            .setTitle("Centang app yang tidak dicatat")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> selected[which] = isChecked }
            .setPositiveButton("Simpan") { _, _ ->
                val result = apps.filterIndexed { i, _ -> selected[i] }.map { it.first }.toSet()
                Prefs.saveIgnored(this, result)
                Toast.makeText(this, "Daftar diabaikan disimpan (${result.size} app)", Toast.LENGTH_SHORT).show()
                onResume() // segarkan tampilan status
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun saveAndSchedule() {
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        val interval = etInterval.text.toString().toIntOrNull()?.coerceIn(15, 1440) ?: 30
        Prefs.save(this, url, token, interval)
        Prefs.saveRules(this, etRules.text.toString())

        // Kirim data: hanya saat ada internet, tiap 15 menit.
        val uploadReq = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "upload", ExistingPeriodicWorkPolicy.UPDATE, uploadReq
        )

        // Cek lokasi tiap 15 menit, tapi GPS hanya dinyalakan sesuai interval yang kamu atur.
        val locReq = PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "location", ExistingPeriodicWorkPolicy.UPDATE, locReq
        )

        Toast.makeText(this, "Tersimpan. Interval lokasi: $interval menit", Toast.LENGTH_LONG).show()
    }
}
