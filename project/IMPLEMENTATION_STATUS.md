# IMPLEMENTATION STATUS — Azm Admin

Updated 2026-11-11. Statuses use the brief's three levels.

## Implemented and tested (build-level)
- Project scaffold: `:app` + `:client`, Gradle 8.12 / AGP 8.9.1 / Kotlin 2.1.0,
  minSdk 30 / targetSdk 36, applicationId `io.github.alnajashia.azmadmin`.
- AIDL `IAzmAdminService` (14 transactions, typed results) + parcelables
  `AzmAdminResult`, `BinderContainer` in `:client`.
- Unit tests for the server's pure logic (`ServerLogic`): 10/10 passing
  (`:app:testDebugUnitTest`, 2026-11-11).
- Debug APK built and signature-checked (debug cert):
  `app-debug.apk`, 11,750,592 bytes,
  SHA-256 `c1f0ae0bc5011025572cb320cdd4df065d4deb04724b890a53b9b6167851fd88`.
- Release AAR for the client built (`client-release.aar`); release APK
  builds unsigned (`app-release-unsigned.apk`) — no dedicated Azm Admin
  signing key exists yet (Stage 15 decision, see below).

## Implemented but NOT fully tested (no device yet)
- Privileged server (`AzmAdminServerMain` via app_process; Looper;
  binder push to the manager provider and to Azm's provider with retries;
  best-effort ServiceManager registration).
- Server-side authorization: trust-on-first-approval records
  (package, UID, cert SHA-256) in the server's own grant file; per-call
  re-verification; manager-only approve/revoke/exit; pending-request flow
  with the manager approval activity launched via `am`.
- Typed operations mirroring Azm's verified Shizuku replacement set,
  each post-verified: provisionDeviceOwner (user_setup_complete
  bypass/restore + direct IDevicePolicyManager + `dpm` fallback + owner
  re-read), grantWriteSecureSettings (pm grant + permission re-check),
  setAccessibilityEnabled (settings write + strict re-read), diagnostics
  (dumpsys account / pm list users / dpm list-owners, same parsers as Azm).
- Manager UI: real binder-derived state, ADB command for this device +
  copy, root start via `su`, pending/authorized lists with approve/revoke,
  authorize-Azm shortcut, diagnostics viewer, stop server. AR + EN,
  Material 3 Day/Night.
- Client library: binder push receiver provider, pull from the manager
  provider, death-recipient handling, typed convenience API.

## Azm integration (M1–M6) — implemented and build-verified 2026-11-11
- Done in the Azm project exactly per the approved list (see
  AZM_INTEGRATION.md): shizuku/ package replaced by azmadmin/ gateway +
  provisioning with the same internal surface and wire payload; Gradle
  deps swapped to the client AAR built from this project; manifest
  provider swapped; install flow opens Azm Admin; Dart texts (ARB EN/AR)
  now say Azm Admin, logic untouched.
- Verification: `flutter analyze` clean, `flutter test` 149/149, Azm
  release APK built and signed with Azm's existing permanent key,
  dex contains zero Shizuku references. Not published and Azm's version
  not bumped — device E2E comes first.

## v1.0.4 — wireless connect endpoint fix (2026-11-11)
- Ahmed's device test of v1.0.3 proved pairing works end-to-end (the
  system paired-devices list shows "AzmAdmin"; the pairing
  notification reported success) but Start failed with
  "wireless start failed: null": discovery found port 39719 — a stale
  announcement, while Settings showed the live connect port as 39287 —
  and the connect went to 127.0.0.1 with the exception's message
  swallowed.
- Fix: PortDiscovery now reports the announced endpoint (host + port);
  pairing and connect use the device's announced network address
  (adbd's TLS listener is interface-bound on many ROMs); StarterActivity
  retries once on the freshest announcement when the first attempt
  fails; every wireless error now carries the exception class, message
  and stack trace instead of a bare "null".
- Build evidence: assemble + unit tests 10/10 + lint 0 errors; signed
  release verified (dedicated Azm Admin cert). On-device confirmation
  still pending Ahmed's run.

## v1.0.3 — Shizuku-shaped wireless flow + v1.0.1 crash fix (2026-11-11)
- v1.0.1 crashed on Ahmed's phone at launch: the mDNS discovery called
  the API-34-only `resolveService(Executor)` overload unguarded
  (NoSuchMethodError on Android 11–13). Fixed with an SDK-guarded
  legacy overload, crash-proof provider install, and an in-app
  CrashLog that shows the last crash's stack trace on next launch.
- The wireless UX was then rebuilt to Shizuku's literal structure
  (from its source): PairingTutorialActivity (notification gating,
  MIUI/local-network warnings, Developer-options shortcut),
  PairingService (foreground service; notification with RemoteInput
  code entry once the pairing announcement is discovered; the port
  travels in the reply intent), and StarterActivity (terminal log:
  automatic connect-port discovery, connect, starter command, binder
  wait, auto-close). Manual port/code fields remain as a fallback.
- Build evidence: assembleDebug + assembleRelease + unit tests 10/10 +
  lintDebug with 0 errors; signed release verified (dedicated Azm
  Admin cert). Pairing/start still to be confirmed on Ahmed's device.

## Wireless-debugging start — implemented in v1.0.1 (2026-11-11)
- Added the same day Ahmed rejected the computer-only start on his
  unrooted phone: the manager now pairs and starts the server entirely
  on-device through wireless debugging, exactly like Shizuku — SPAKE2
  pairing with the 6-digit code, TLS connect, and the same app_process
  starter command executed over the ADB shell stream
  (`manager/WirelessStarter.kt`, `manager/AzmAdbManager.kt` — the
  manager's own persistent RSA ADB identity). mDNS discovery pre-fills
  the pairing/connect ports; manual entry remains. Built on
  libadb-android 3.1.1 + Conscrypt + BouncyCastle (see
  THIRD_PARTY_LICENSES.md). Build-verified (assembleDebug, unit tests
  10/10, APK v1.0.1 code 2) — the pairing/start flow itself is still
  **untested on a real device** until Ahmed runs it.

## Not implemented yet
- Dedicated Azm Admin release signing (new keystore to be created at
  Stage 15 and backed up to the private Drive folder — never to GitHub).
- The Google Drive `azm admin` folder — on Ahmed's explicit go-ahead.
  (Code push and Release v1.0.0 were published 2026-11-11 at his
  request; the v1.0.1 wireless build follows the same route on his word.)
- Real-device testing of every server behaviour (needs Ahmed's phone):
  ADB start, binder push/pull, approval round-trip, provisioning Azm,
  revocation, unauthorized rejection, server stop/restart, reboot
  behaviour. Until then all server behaviour is **untested on device**.
