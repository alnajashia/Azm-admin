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

## Not implemented yet
- Wireless-debugging pairing/start inside the manager (later increment,
  as recorded in the handover; the ADB-command and root paths are the
  v1 startup mechanisms, and the UI must not fake a wireless button).
- Dedicated Azm Admin release signing (new keystore to be created at
  Stage 15 and backed up to the private Drive folder — never to GitHub).
- GitHub push to `alnajashia/Azm-admin` and the Google Drive `azm admin`
  folder — both on Ahmed's explicit go-ahead.
- Real-device testing of every server behaviour (needs Ahmed's phone):
  ADB start, binder push/pull, approval round-trip, provisioning Azm,
  revocation, unauthorized rejection, server stop/restart, reboot
  behaviour. Until then all server behaviour is **untested on device**.
