// Google Apps Script: menerima data dari app dan menulis ke dua spreadsheet terpisah.
// Isi Script Properties (Project Settings > Script properties):
//   TOKEN        = token rahasia bebas (contoh: string acak panjang)
//   NOTIF_SHEET  = ID spreadsheet untuk notifikasi
//   LOC_SHEET    = ID spreadsheet untuk lokasi (file berbeda)

function doPost(e) {
  // Kunci supaya dua kiriman yang datang bersamaan tidak saling menimpa baris di sheet.
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    return handlePost(e);
  } finally {
    lock.releaseLock();
  }
}

function handlePost(e) {
  const props = PropertiesService.getScriptProperties();
  const body = JSON.parse(e.postData.contents);

  if (body.token !== props.getProperty('TOKEN')) {
    return ContentService.createTextOutput('forbidden');
  }

  const notifSs = SpreadsheetApp.openById(props.getProperty('NOTIF_SHEET'));
  const locSs = SpreadsheetApp.openById(props.getProperty('LOC_SHEET'));
  const notifRows = [];
  const locRows = [];

  // Waktu diformat sebagai teks dengan zona WIB, supaya tidak tergantung zona waktu sheet.
  const fmt = function (ms) {
    return Utilities.formatDate(new Date(ms), 'Asia/Jakarta', 'dd/MM/yyyy HH:mm:ss');
  };

  // Lewati data yang ID-nya sudah ada di sheet, supaya kiriman ulang tidak membuat duplikat.
  const notifSh = getOrCreateSheet(notifSs, 'Notifikasi', ['Waktu', 'Aplikasi', 'Judul', 'Isi', 'ID']);
  const locSh = getOrCreateSheet(locSs, 'Lokasi', ['Waktu', 'Lat', 'Lon', 'Akurasi (m)', 'ID', 'Google Maps']);
  const seenNotif = existingIds(notifSh);
  const seenLoc = existingIds(locSh);
  const lastText = lastTextByTitle(notifSh);

  body.items.forEach(function (it) {
    if (!it.id) return;
    if (it.kind === 'notif' && !seenNotif.has(it.id)) {
      seenNotif.add(it.id);
      // Lewati notifikasi yang aplikasi, judul, dan isinya sama persis dengan yang terakhir tercatat
      // dan masuk dalam DUP_WINDOW_MS. Lewat dari itu dianggap pesan baru.
      const key = it.app + '|' + it.title;
      const prev = lastText.get(key);
      if (prev && prev.text === String(it.text) && it.ts - prev.ts <= DUP_WINDOW_MS) return;
      lastText.set(key, { text: String(it.text), ts: it.ts });
      notifRows.push([fmt(it.ts), it.app, it.title, it.text, it.id]);
    } else if (it.kind === 'loc' && !seenLoc.has(it.id)) {
      locRows.push([fmt(it.ts), it.lat, it.lon, it.acc, it.id, mapsLink(it.lat, it.lon)]);
      seenLoc.add(it.id);
    }
  });

  if (notifRows.length) {
    notifSh.getRange(notifSh.getLastRow() + 1, 1, notifRows.length, 5).setValues(notifRows);
  }
  if (locRows.length) {
    locSh.getRange(locSh.getLastRow() + 1, 1, locRows.length, 6).setValues(locRows);
  }

  return ContentService.createTextOutput('ok');
}

// Ambil semua ID yang sudah tercatat di kolom E (ID).
function existingIds(sh) {
  const ids = new Set();
  const last = sh.getLastRow();
  if (last > 1) {
    sh.getRange(2, 5, last - 1, 1).getValues().forEach(function (r) {
      if (r[0]) ids.add(String(r[0]));
    });
  }
  return ids;
}

// Notifikasi dengan isi sama dalam rentang ini dianggap berulang (1 menit).
const DUP_WINDOW_MS = 60 * 1000;

// Isi dan waktu notifikasi terakhir per (aplikasi, judul) dari 500 baris terakhir di sheet.
// Waktu diambil dari bagian akhir ID ("n|key|postTime"), bukan dari kolom Waktu yang berupa teks.
function lastTextByTitle(sh) {
  const map = new Map();
  const last = sh.getLastRow();
  if (last > 1) {
    const first = Math.max(2, last - 499);
    sh.getRange(first, 2, last - first + 1, 4).getValues().forEach(function (r) {
      const ts = Number(String(r[3]).split('|').pop());
      map.set(r[0] + '|' + r[1], { text: String(r[2]), ts: ts });
    });
  }
  return map;
}

// Rumus HYPERLINK supaya sel bisa diklik dan membuka Google Maps di titik lat/lon.
function mapsLink(lat, lon) {
  return '=HYPERLINK("https://www.google.com/maps?q=' + lat + ',' + lon + '","Buka Maps")';
}

// Jalankan sekali dari editor Apps Script untuk mengisi kolom Google Maps di baris lokasi lama.
function backfillMapLinks() {
  const props = PropertiesService.getScriptProperties();
  const sh = SpreadsheetApp.openById(props.getProperty('LOC_SHEET')).getSheetByName('Lokasi');
  const last = sh.getLastRow();
  if (last < 2) return;
  sh.getRange(1, 6).setValue('Google Maps');
  const rows = sh.getRange(2, 2, last - 1, 2).getValues();
  const links = rows.map(function (r) { return [mapsLink(r[0], r[1])]; });
  sh.getRange(2, 6, links.length, 1).setValues(links);
}

function getOrCreateSheet(ss, name, header) {
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
  }
  // Pastikan header lengkap ada di baris 1, termasuk kolom ID (tambahkan kalau belum ada).
  if (sh.getRange(1, header.length).getValue() === '') {
    sh.getRange(1, 1, 1, header.length).setValues([header]);
    sh.setFrozenRows(1);
  }
  return sh;
}
