// Google Apps Script: menerima data dari app dan menulis ke dua spreadsheet terpisah.
// Isi Script Properties (Project Settings > Script properties):
//   TOKEN        = token rahasia bebas (contoh: string acak panjang)
//   NOTIF_SHEET  = ID spreadsheet untuk notifikasi
//   LOC_SHEET    = ID spreadsheet untuk lokasi (file berbeda)

function doPost(e) {
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
  const locSh = getOrCreateSheet(locSs, 'Lokasi', ['Waktu', 'Lat', 'Lon', 'Akurasi (m)', 'ID']);
  const seenNotif = existingIds(notifSh);
  const seenLoc = existingIds(locSh);

  body.items.forEach(function (it) {
    if (!it.id) return;
    if (it.kind === 'notif' && !seenNotif.has(it.id)) {
      notifRows.push([fmt(it.ts), it.app, it.title, it.text, it.id]);
      seenNotif.add(it.id);
    } else if (it.kind === 'loc' && !seenLoc.has(it.id)) {
      locRows.push([fmt(it.ts), it.lat, it.lon, it.acc, it.id]);
      seenLoc.add(it.id);
    }
  });

  if (notifRows.length) {
    notifSh.getRange(notifSh.getLastRow() + 1, 1, notifRows.length, 5).setValues(notifRows);
  }
  if (locRows.length) {
    locSh.getRange(locSh.getLastRow() + 1, 1, locRows.length, 5).setValues(locRows);
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
