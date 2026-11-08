# VirtualCam ONVIF for Android 📷📡

Aplikasi **Android Virtual Camera** & **ONVIF/RTSP Server** yang memungkinkan ponsel Android dijadikan kamera virtual yang dikenali oleh aplikasi lain, memutar file video MP4 berulang (*looping*), melakukan *screen mirror* (tampilan layar), serta dapat diakses melalui jaringan lokal IP menggunakan protokol **ONVIF** dan **RTSP**.

---

## ✨ Fitur Utama

1. **Dikenali Sebagai Kamera Sistem oleh Aplikasi Lain:**
   - **Mode Intent Chooser (Tanpa Root):** Mendaftarkan diri sebagai penyedia kamera (`android.media.action.IMAGE_CAPTURE` dan `VIDEO_CAPTURE`). Ketika aplikasi lain (seperti browser, webview, aplikasi absensi/KYC, Telegram, form dokumen) meminta kamera, dialog sistem Android akan memunculkan **VirtualCam ONVIF** sebagai salah satu pilihan kamera.
   - **Mode Hook LSPosed / Xposed / LSPatch (Untuk Aplikasi Panggilan Langsung):** Dilengkapi modul Xposed bawaan (`CameraHook.kt`) untuk menyuntikkan video virtual langsung ke aplikasi yang membuka hardware kamera tanpa intent (seperti WhatsApp Video Call, Zoom, Google Meet, TikTok, dan Google Chrome).
   - **Floating Controller Window:** Jendela preview mengambang (*Picture-in-Picture*) yang dapat digeser di atas aplikasi lain saat sedang melakukan video call.

2. **Multi-Pilihan Sumber Media:**
   - **Kamera Nyata Fisik (Depan & Belakang):** Akses langsung kamera fisik perangkat dengan koreksi orientasi otomatis (sensor depan 270° dan belakang 90° sehingga gambar selalu tegak normal, tidak miring).
   - **File Video MP4 Lokal:** Pilih file video dari galeri/memori internal, otomatis berputar terus menerus (*seamless loop*) dengan pendeteksian aspek rasio asli (16:9, 9:16, 4:3).
   - **Tampilan Layar (Screen Mirror):** Menggunakan Android `MediaProjection` API untuk menangkap tampilan layar ponsel secara real-time (game, emulator, browser, presentasi) dan menyiarkannya sebagai feed kamera.
   - **Test Pattern SMPTE:** Pola uji warna standar TV dengan jam digital real-time dan animasi kotak bergerak untuk verifikasi instan.

3. **Kontrol Interaktif & Anti-Distorsi Aspek Rasio (OpenGL ES):**
   - **Anti-Distorsi (No Stretch):** Matriks MVP shader OpenGL mencegah gambar tertarik atau gepeng.
   - 🔄 **Rotasi Fleksibel:** Putar 0°, 90°, 180°, atau 270° secara langsung.
   - 🪞 **Mode Cermin:** Toggle cermin horizontal untuk selfie atau video.
   - 📐 **Mode Skala:** Pilih Fit (rasio asli dengan letterbox) atau Crop (isi layar penuh).

4. **Akses Jaringan IP (ONVIF & RTSP):**
   - **WS-Discovery Server (UDP 3702):** Terdeteksi otomatis oleh aplikasi *ONVIF Device Manager (ODM)*, NVR CCTV, Synology Surveillance Station, Blue Iris, Shinobi, dan iSpy.
   - **ONVIF SOAP HTTP Server (Port 8080):** Menyediakan endpoint `GetDeviceInformation`, `GetCapabilities`, `GetProfiles`, `GetStreamUri`, dan snapshot HTTP `/snapshot.jpg`.
   - **RTSP Live H.264 Server (Port 8554):** Kompatibel penuh dengan VLC Media Player (`rtsp://<IP-HP>:8554/live`), OBS Studio, ffmpeg, dan NVR CCTV via TCP maupun UDP.

---

## 🚀 Cara Menggunakan

### A. Menggunakan Melalui IP (ONVIF / RTSP di PC / NVR)
1. Buka aplikasi **VirtualCam ONVIF** di ponsel.
2. Pastikan ponsel terhubung ke jaringan Wi-Fi yang sama dengan PC/NVR.
3. Pilih sumber video: **File Video (MP4)** atau **Tampilan Layar (Screen Mirror)**.
4. Tekan tombol hijau **"Mulai Streaming & ONVIF"**.
5. Buka di PC/Laptop:
   - **VLC Media Player:** Menu *Media -> Open Network Stream* -> Masukkan `rtsp://<IP-HP>:8554/live`.
   - **ONVIF Device Manager:** Masukkan URL `http://<IP-HP>:8080/onvif/device_service`.

---

### B. Menggunakan Sebagai Kamera di Aplikasi Lain

#### Cara 1: Aplikasi dengan Intent Kamera (Tanpa Root)
- Buka aplikasi yang membutuhkan upload foto/video atau scan kamera (misal Chrome, Telegram, Webview).
- Pilih "Ambil Gambar / Take Photo".
- Saat Android memunculkan pilihan aplikasi kamera (*Complete action using...*), pilih **VirtualCam ONVIF**.
- Preview video virtual akan muncul. Tekan tombol kamera untuk mengirimkan frame sebagai foto atau rekaman video ke aplikasi tersebut.

#### Cara 2: Panggilan Video Langsung (WhatsApp, Zoom, Meet) via LSPosed
- Buka aplikasi **LSPosed Manager**.
- Aktifkan modul **VirtualCam ONVIF**.
- Pada daftar target aplikasi, centang aplikasi yang ingin diganti kameranya (misalnya WhatsApp atau Zoom).
- Simpan file video yang ingin diputar di memori internal dengan nama `/sdcard/virtualcam.mp4`.
- Buka panggilan video di WhatsApp/Zoom; kamera otomatis menampilkan video tersebut!

---

## 🛠️ Arsitektur & Teknologi

- **Bahasa:** Kotlin & AndroidX
- **Video Encoder:** Hardware H.264 MediaCodec (AVC)
- **Streaming:** Custom High-Performance RTSP 1.0 Server & RFC 6184 H.264 RTP Packetizer
- **Discovery:** Multicast UDP WS-Discovery (239.255.255.250:3702)
- **CI/CD:** GitHub Actions otomatis mem-build APK (`app-debug.apk`) dan merilisnya ke halaman GitHub Releases.

---

## 📦 Download APK

APK yang sudah terkompilasi dapat diunduh langsung melalui tab [Releases](../../releases) atau tab [Actions](../../actions) pada repositori ini setelah pipeline CI selesai berjalan.
