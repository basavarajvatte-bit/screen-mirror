# Screen Mirror

Mirror an Android phone's live screen to an iPad (Safari).

## Parts
| Folder | What it is |
|---|---|
| `app/` | Android app (Kotlin). Captures the screen and sends it. |
| `.github/workflows/android.yml` | Builds the APK in the cloud and publishes it on Releases. |
| `web/` *(coming)* | iPad receiver website. |
| `server/` *(coming)* | Signaling server (Cloudflare Worker). |

## Download the app
Latest APK: https://github.com/basavarajvatte-bit/screen-mirror/releases/latest/download/ScreenMirror.apk

## Status
- [x] Phase 1: Architecture
- [x] Phase 2: Android screen capture (foreground service, notification, FPS/resolution)
- [ ] Phase 3: WebRTC streaming
- [ ] Phase 4: iPad receiver
- [ ] Phase 5: QR pairing
- [ ] Phase 6–10: Wi-Fi optimisation, TURN, performance, security, docs

Note: `app/debug.keystore` is a standard *debug* signing key (password `android`), kept so updates install over old versions. Not a secret.
