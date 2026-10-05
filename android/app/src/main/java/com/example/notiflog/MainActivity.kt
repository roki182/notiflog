package com.example.notiflog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import java.util.Locale
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

    // Pasang bahasa pilihan pengguna (bawaan Inggris), bukan bahasa sistem.
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration)
        val locale = Locale(Prefs.lang(base))
        Locale.setDefault(locale)
        config.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        etUrl = EditText(this).apply { hint = getString(R.string.hint_url) }
        etToken = EditText(this).apply { hint = getString(R.string.hint_token) }
        etInterval = EditText(this).apply {
            hint = getString(R.string.hint_interval)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val btnNotif = Button(this).apply {
            text = getString(R.string.btn_notif_access)
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        val btnLoc = Button(this).apply {
            text = getString(R.string.btn_location_perm)
            setOnClickListener {
                locPermRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }
        val btnBgLoc = Button(this).apply {
            text = getString(R.string.btn_app_settings)
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
        val btnBattery = Button(this).apply {
            text = getString(R.string.btn_battery)
            setOnClickListener {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_battery_already), Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        val btnIgnore = Button(this).apply {
            text = getString(R.string.btn_ignore_apps)
            setOnClickListener { pickIgnoredApps() }
        }
        val btnLanguage = Button(this).apply {
            text = getString(R.string.btn_language, langName(Prefs.lang(this@MainActivity)))
            setOnClickListener { pickLanguage() }
        }
        val btnSave = Button(this).apply {
            text = getString(R.string.btn_save)
            setOnClickListener { saveAndSchedule() }
        }

        etRules = EditText(this).apply {
            hint = getString(R.string.hint_rules)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        tvStatus = TextView(this)

        // Urutan: isian, tombol & pengaturan, lalu status di paling bawah.
        root.addView(TextView(this).apply { text = getString(R.string.app_title); textSize = 20f })
        root.addView(etUrl)
        root.addView(etToken)
        root.addView(etInterval)
        root.addView(etRules)
        root.addView(btnNotif)
        root.addView(btnLoc)
        root.addView(btnBgLoc)
        root.addView(android.widget.CheckBox(this).apply {
            text = getString(R.string.chk_skip_ongoing)
            isChecked = Prefs.skipOngoing(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.saveSkipOngoing(this@MainActivity, checked) }
        })
        root.addView(btnBattery)
        root.addView(btnIgnore)
        root.addView(btnLanguage)
        root.addView(btnSave)
        root.addView(tvStatus)
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

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
        tvStatus.text = getString(R.string.status_listener_connected, t("connected")) + "\n" +
            getString(R.string.status_listener_disconnected, t("disconnected")) + "\n" +
            getString(R.string.status_last_notif, t("notif")) + "\n" +
            getString(R.string.status_last_worker, t("worker")) + "\n" +
            getString(R.string.status_last_upload, t("upload")) + "\n" +
            getString(R.string.status_queue, Store.readAll(this).size) + "\n" +
            getString(
                R.string.status_ignored_apps,
                Prefs.ignored(this).sorted().joinToString(", ").ifEmpty { "-" }
            ) + "\n" +
            getString(R.string.status_recent_header) + "\n" + recentNotifs() + "\n" +
            getString(R.string.status_exit_header) + "\n" + exitReasons(fmt)
    }

    // 8 notifikasi terakhir yang diterima app, yang terbaru di atas, beserta statusnya.
    private fun recentNotifs(): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val rows = Prefs.recent(this).takeLast(8).reversed().mapNotNull {
            val p = it.split("|", limit = 4)
            if (p.size < 4) null
            else "${fmt.format(java.util.Date(p[0].toLongOrNull() ?: 0L))} [${statusLabel(p[1])}] ${p[2]} - ${p[3]}"
        }
        return if (rows.isEmpty()) "-" else rows.joinToString("\n")
    }

    // Status disimpan di HP sebagai kata kunci tetap; hanya labelnya yang diterjemahkan.
    private fun statusLabel(key: String) = when (key) {
        "dicatat" -> getString(R.string.recent_logged)
        "ongoing" -> getString(R.string.recent_ongoing)
        "diabaikan" -> getString(R.string.recent_ignored)
        else -> key
    }

    // Android mencatat kenapa proses app dimatikan (Android 11 ke atas). Tampilkan 5 terakhir.
    private fun exitReasons(fmt: java.text.SimpleDateFormat): String {
        if (android.os.Build.VERSION.SDK_INT < 30) return getString(R.string.exit_needs_android11)
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
        2 -> getString(R.string.exit_signaled)
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> getString(R.string.exit_user_stopped)
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
            .setTitle(getString(R.string.dialog_ignore_title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> selected[which] = isChecked }
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val result = apps.filterIndexed { i, _ -> selected[i] }.map { it.first }.toSet()
                Prefs.saveIgnored(this, result)
                Toast.makeText(this, getString(R.string.toast_ignored_saved, result.size), Toast.LENGTH_SHORT).show()
                onResume() // segarkan tampilan status
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun langName(code: String) = if (code == "in") "Bahasa Indonesia" else "English"

    private fun pickLanguage() {
        val codes = arrayOf("en", "in")
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_language_title)
            .setSingleChoiceItems(
                codes.map { langName(it) }.toTypedArray(), codes.indexOf(Prefs.lang(this))
            ) { dialog, which ->
                dialog.dismiss()
                if (codes[which] != Prefs.lang(this)) {
                    Prefs.saveLang(this, codes[which])
                    recreate()
                }
            }
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

        Toast.makeText(this, getString(R.string.toast_saved, interval), Toast.LENGTH_LONG).show()
    }
}
