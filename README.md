# OpenScreenRecorderApp
This app is made because my device do not have any built-in screen recorder and most of the Play Store Apps collects data and show ads.


# 📹 Open Screen Recorder — Features Overview

### 🎬 Screen Recording & Performance
* **Flexible Video Quality & Resolutions:** Supports dynamic resolution scaling maintaining your device aspect ratio:
  * Max (Native screen resolution)
  * 4K, 2K, 1080p, 720p, 480p, 360p, 240p
* **Pause & Resume:** Seamlessly pause and resume recordings at any time without video/audio desync or time-jump glitches.

---

### 🎙️ Audio Recording Options
* **Microphone Recording:** Capture voiceovers and ambient audio via microphone.
* **System Audio Capture:** Record internal device sound (apps, games, media).
* **Dual Audio Mixing:** Record and mix both **Microphone** and **System Audio** simultaneously into a clean AAC audio stream.

---

### 🎛️ Floating Controls & Quick Access
* **Floating Start Widget:** A quick-start floating button on screen to begin recording from any app.
* **Auto-Start Floating Window:** Option to automatically launch the floating start widget when opening or returning to the app.
* **Recording Controls Overlay:**
  * Draggable floating overlay during recording showing live elapsed time.
  * Quick access to **Pause**, **Resume**, and **Stop** buttons.
  * Auto-collapsing and side-docking behavior with smooth animations.
* **Quick Settings Tile:** Control recording directly from your Android Quick Settings panel (notification shade) with a single tap.

---

### 👈 Visual Touches & Feedback
* **Show Touches:** Automatically enables visual touch feedback (tap dots) on screen while recording, and safely restores original system settings upon stopping.

---

### 📁 Storage & File Customization
* **Custom Storage Location:** Choose any directory on internal storage or SD card via the Android Storage Access Framework (SAF) folder picker (defaults to `DCIM/Recordings`).
* **Custom File Prefix:** Set a custom prefix for generated video filenames (e.g., `Screen_Record_`).
* **Hidden Files Option:** Support for prefixes starting with `.` to keep recordings hidden from standard media galleries if desired.
* **Date & Time Formatting:** Customizable timestamp patterns (`yyyyMMdd_HHmmss`, `yyyy-MM-dd_HH-mm-ss`, etc.).

---

### 🔔 Smart Notifications
* **Ongoing Foreground Notification:** Low-priority ongoing notification while recording with interactive Pause/Resume and Stop actions.
* **Recording Saved Notification:** Instant completion notification featuring a large video thumbnail preview and tap-to-play shortcut.
