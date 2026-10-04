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

  body.items.forEach(function (it) {
    if (it.kind === 'notif') {
      notifRows.push([fmt(it.ts), it.app, it.title, it.text]);
    } else if (it.kind === 'loc') {
      locRows.push([fmt(it.ts), it.lat, it.lon, it.acc]);
    }
  });

  if (notifRows.length) {
    const sh = getOrCreateSheet(notifSs, 'Notifikasi', ['Waktu', 'Aplikasi', 'Judul', 'Isi']);
    sh.getRange(sh.getLastRow() + 1, 1, notifRows.length, 4).setValues(notifRows);
  }
  if (locRows.length) {
    const sh = getOrCreateSheet(locSs, 'Lokasi', ['Waktu', 'Lat', 'Lon', 'Akurasi (m)']);
    sh.getRange(sh.getLastRow() + 1, 1, locRows.length, 4).setValues(locRows);
  }

  return ContentService.createTextOutput('ok');
}

function getOrCreateSheet(ss, name, header) {
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
  }
  // Pastikan header ada di baris 1 (tambahkan kalau sheet masih kosong).
  if (sh.getLastRow() === 0) {
    sh.appendRow(header);
    sh.setFrozenRows(1);
  }
  return sh;
}
