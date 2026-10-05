# NotifLog

Aplikasi Android yang mencatat notifikasi dan lokasi HP, lalu mengirimnya ke Google Sheet lewat Google Apps Script.

- `android/` — aplikasi Android (Kotlin)
- `apps-script/Code.gs` — server penerima data, menulis ke satu spreadsheet (`NOTIF_SHEET`) dengan dua tab: Notifikasi dan Lokasi. Kalau sebelumnya lokasi ada di file terpisah (`LOC_SHEET`), jalankan `migrateLocations` sekali dari editor Apps Script untuk menyalin datanya ke tab Lokasi yang baru.
- `.github/workflows/build.yml` — build APK otomatis

## Cara kerja singkat

- Notifikasi dicatat saat masuk, lalu diunggah 2 detik kemudian.
- Lokasi diambil oleh worker tiap 15 menit, tapi GPS hanya dinyalakan sesuai interval yang diatur (default 30 menit). Lokasi tidak ikut dikirim saat notifikasi masuk.
- Semua data ditulis dulu ke antrian lokal (`queue.jsonl`), lalu dikirim saat ada internet.
- Notifikasi dengan aplikasi, judul, dan isi yang sama persis dengan yang terakhir tercatat dilewati, selama masuk dalam 1 menit dari yang tercatat (`DUP_WINDOW_MS` di `Code.gs`). Lewat dari itu dicatat lagi.
- Notifikasi "ongoing" (yang menempel terus di status bar, seperti speed meter, pemutar musik, dan navigasi) diabaikan secara bawaan. Bisa dimatikan lewat kotak centang di layar utama. Pengabaian ini tidak berlaku untuk trigger lokasi.
- Trigger lokasi: notifikasi yang cocok dengan salah satu aturan di kolom "Trigger lokasi" langsung memicu pengambilan lokasi tanpa menunggu interval, lalu dikirim ke sheet. Satu aturan per baris, format `aplikasi|judul|isi`. Bagian yang dikosongkan berarti "apa saja", pencocokan "mengandung" tanpa membedakan huruf besar/kecil, dan bagian aplikasi cocok ke nama app maupun nama package. Contoh: `whatsapp||darurat` (pesan WhatsApp yang isinya mengandung "darurat"), `com.bank.app` (semua notifikasi app itu). Baris berawalan `#` diabaikan. Ada jeda 30 detik antar pengambilan, dan app yang ada di daftar abaikan tetap bisa memicu lokasi.
- Sheet Lokasi punya kolom "Google Maps" berisi tautan ke titik lokasi. Untuk baris lama, jalankan fungsi `backfillMapLinks` sekali dari editor Apps Script.

## Pengaturan HP agar data tidak berhenti

Tanpa pengaturan ini, sistem Android bisa mematikan app di latar belakang (gejala: data berhenti masuk sekitar jam 4 pagi). Setelah install, tekan juga tombol "Matikan Optimasi Baterai" di dalam app.

### Samsung (One UI), contoh Galaxy A07
1. Pengaturan → Aplikasi → NotifLog → Baterai → **Tidak dibatasi**.
2. Pengaturan → Baterai → Batas penggunaan di latar belakang → matikan **Tidurkan app yang tidak digunakan**. Pastikan NotifLog tidak ada di daftar **App tidur** dan **App tidur dalam**.
3. Pengaturan → Perawatan perangkat → ⋮ → Pengaturan lanjutan → matikan **Optimasi otomatis harian** dan **Mulai ulang otomatis**.
4. Di Recent Apps, tekan lama ikon NotifLog → **Kunci app ini**.

### Vivo (Funtouch OS), contoh Y12
1. Pengaturan → Baterai → **Konsumsi daya latar belakang tinggi** → izinkan NotifLog.
2. Pengaturan → Aplikasi → **Mulai otomatis** (Autostart) → aktifkan NotifLog.
3. Pengaturan → Baterai → Manajer app → NotifLog → **Izinkan aktivitas latar belakang**.
4. Kunci app di Recent Apps (tarik kartu app ke bawah).

### Diagnosa
Layar utama app menampilkan kapan listener terakhir tersambung/terputus, kapan notifikasi dan upload terakhir, jumlah antrian, dan 8 notifikasi terbaru yang diterima app beserta statusnya (`dicatat`, `ongoing`, atau `diabaikan`). Daftar ini hanya disimpan di HP dan berguna untuk mencari tahu kenapa suatu notifikasi, misalnya missed call, tidak muncul di sheet. Kalau notifikasinya tidak ada di daftar sama sekali, berarti HP tidak meneruskannya ke app. Setelah data berhenti, buka app dan lihat status itu:

- Listener terputus dan tidak tersambung lagi: sistem mematikan listener, periksa lagi pengaturan di atas.
- Listener tersambung tapi upload terakhir sudah lama: masalah jaringan atau Apps Script.
- Worker terakhir jalan sudah lama: app di-sleep total oleh sistem.

## Setelah memperbarui Code.gs

Salin isi `apps-script/Code.gs` ke editor Apps Script, lalu **Deploy → Manage deployments → Edit → New version** agar perubahan berlaku di URL Web App yang sama.
