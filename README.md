# XiaoZhi Android Client — Versi Bahasa Indonesia 🇮🇩

Modifikasi dari [luo785859020/xiaozhi-android-client](https://github.com/luo785859020/xiaozhi-android-client) v0.3.0 (lisensi MIT) khusus untuk pengguna Indonesia.

## Yang Diubah

- ✅ **Voice-to-text Bahasa Indonesia** — Perbaikan bug: bahasa speech recognition sebelumnya di-hardcode ke Mandarin (`zh-CN`), sekarang menggunakan `id-ID`
- ✅ **Build otomatis** — APK terbuild otomatis via GitHub Actions setiap ada perubahan kode

## Cara Mendapatkan APK

1. Buka tab **Actions** di repo ini
2. Pilih workflow **Build APK Indonesia** yang sudah selesai
3. Download dari bagian **Artifacts** → `xiaozhi-id-apk`
4. Install file APK di HP/tablet Android

## Cara Pakai

1. Buka aplikasi
2. Isi **OTA URL**: `https://api.tenclass.net/xiaozhi/ota/`
3. Isi **WebSocket URL**: `wss://api.tenclass.net/xiaozhi/v1/`
4. Ikuti proses aktivasi (kode verifikasi muncul sebagai teks di aplikasi)
5. Bicara dalam Bahasa Indonesia — sekarang dikenali dengan benar!

## Catatan

- Aplikasi ini terhubung ke **server resmi xiaozhi.me** (api.tenclass.net)
- Konfigurasi agent (bahasa, suara, persona) dilakukan di [xiaozhi.me/console](https://xiaozhi.me/console/)
- Untuk kontrol Home Assistant via suara, gunakan fitur MCP di konsol xiaozhi.me

## Lisensi

MIT — mengikuti lisensi repo aslinya.
