# TESTING — Azm Admin

## Done (2026-11-11, build-level)
- `:app:testDebugUnitTest` — 10/10 passing (`ServerLogicTest`: component
  flattening, accessibility list add/remove/empty, `dpm list-owners`,
  `dumpsys account` and `pm list users` parsing, SHA-256 helper).
- `:app:assembleDebug` and `:app:assembleRelease` build clean;
  `lintVitalRelease` passes as part of the release build.
- Debug APK verified with apksigner (debug certificate) and aapt2:
  package `io.github.alnajashia.azmadmin`, versionCode 1 / versionName
  1.0.0, minSdk 30, targetSdk 36.

## Pending (needs Ahmed's real device — nothing below is verified yet)
Fresh install and first launch; ADB start (host command); server process
and UID check; binder push to the manager and pull by Azm; authorization
request → approval → recorded grant; denial leaves no grant; revocation
takes effect on the next call; unauthorized client rejected;
provisionDeviceOwner end-to-end for Azm; WRITE_SECURE_SETTINGS grant;
accessibility toggle; diagnostics contents; stop server from the
manager; server restart; device reboot leaves the server stopped and the
manager says so; root start on a rooted device/emulator if available;
Azm integration regression (Azm analyze + its 149 tests after M1–M6).

Each device test will be recorded here with device model, Android
version, build, date, result, and evidence when it is actually run.
