# Open Screen Recorder

An open-source Android screen recorder built for devices that don't have a built-in screen recorder.

The goal is simple: **record your screen without ads, unnecessary data collection, or intrusive services.**

## ✨ Features

### 🎬 Screen Recording & Performance

* **Flexible Video Quality & Resolutions**

  * Max — Native screen resolution
  * 4K
  * 2K
  * 1080p
  * 720p
  * 480p
  * 360p
  * 240p
* Automatically maintains the device's screen aspect ratio when scaling resolutions.
* **Pause & Resume** recordings seamlessly without audio/video desync or time-jump glitches.
* Configurable recording performance for different device capabilities.

### 🎙️ Audio Recording

* **Microphone Recording** — Capture voiceovers, commentary, and ambient audio.
* **System Audio Capture** — Record internal device sounds from supported apps, games, and media.
* **Dual Audio Mixing** — Record microphone and system audio simultaneously and mix them into a clean AAC audio stream.
* Control which audio sources are enabled before starting a recording.

### 🎛️ Floating Controls & Quick Access

* **Floating Start Widget** — Start a recording from anywhere without returning to the app.
* **Auto-Start Floating Window** — Automatically show the floating start widget when opening or returning to the app.
* **Recording Controls Overlay**

  * Live elapsed recording time
  * Pause / Resume
  * Stop
  * Draggable positioning
  * Auto-collapsing controls
  * Side-docking behavior
  * Smooth animations
* **Quick Settings Tile** — Start and control recording directly from Android's Quick Settings panel.

### ✏️ On-Screen Tools

Powerful tools for explaining, demonstrating, and annotating content while recording.

* **Drawing / Pen Tool** — Draw directly over the screen while recording.
* **Eraser** — Remove drawings and annotations.
* **Highlight Tool** — Emphasize important areas of the screen.
* **Touch Indicator** — Make taps and interactions easier to follow.
* **Screen Annotation** — Mark up the screen in real time.
* **Text Overlay** — Add text directly to the recording.
* **Shapes & Arrows** — Draw arrows, rectangles, circles, and other shapes.
* **Undo / Redo** — Easily correct or restore annotations.
* **Whiteboard Mode** — Use the screen as a simple digital whiteboard.
* **Live Pointer / Cursor** — Highlight the area currently being interacted with.
* **Floating Recording Controls** — Access recording and annotation tools without leaving the current app.

### ⏱️ Recording Automation

* **Automatic Stop Timer** — Automatically stop recording after a specified duration.
* **Scheduled Recording** — Schedule a recording to start at a specific date and time.
* **Auto-Start Recording** — Automatically begin recording when configured conditions are met.

### 👈 Touch Feedback

* **Show Touches** — Display visual tap indicators while recording.
* Automatically applies the required system touch-feedback setting when enabled.
* Safely restores the original system setting after recording stops.

### 📸 Screenshot Capture & Editing

Capture screenshots without leaving the app.

* One-tap screenshot capture.
* Capture the current screen while recording or when idle.
* Screenshot preview.
* Crop screenshots.
* Draw and annotate screenshots.
* Add text, arrows, shapes, and highlights.
* Undo / redo editing actions.
* Save screenshots to the configured storage location.
* Share screenshots directly with other apps.

### 📁 Storage & File Customization

* **Custom Storage Location** — Choose any directory on internal storage or an SD card using Android's Storage Access Framework (SAF).
* Defaults to `DCIM/Recordings`.
* **Custom File Prefix** — Configure your own filename prefix, such as `Screen_Record_`.
* **Hidden Files** — Use a filename prefix beginning with `.` to keep recordings hidden from standard media galleries when desired.
* **Custom Date & Time Formatting** — Configure timestamp patterns such as:

  * `yyyyMMdd_HHmmss`
  * `yyyy-MM-dd_HH-mm-ss`
  * and other supported date/time patterns.
* Automatic, unique filenames for recordings.

### 🔔 Smart Notifications

* **Ongoing Foreground Notification**

  * Displays recording status while recording.
  * Low-priority notification.
  * Pause / Resume action.
  * Stop action.
* **Recording Saved Notification**

  * Appears immediately after recording finishes.
  * Includes a large video thumbnail preview.
  * Provides a quick shortcut to play the recording.

## 🔐 Privacy First

Open Screen Recorder is designed with privacy in mind.

* **No advertisements**
* **No unnecessary data collection**
* **No intrusive tracking**
* Recordings stay on your device unless you choose to share them.
* Uses Android's built-in screen-capture and storage APIs wherever possible.

> **Your screen. Your recordings. Your data.**
