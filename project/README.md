# Azm Admin

Azm Admin is Ahmed's own privileged-service manager for the Azm ecosystem:
a standalone Android app whose privileged server provides exactly the
setup-time operations Azm previously obtained from the separate Shizuku
app — nothing more.

- **Package:** `io.github.alnajashia.azmadmin` · minSdk 30 · targetSdk 36
- **Design:** Strategy B (see `../AZM_ADMIN_ARCHITECTURE.md`) — Azm Admin's
  own typed, allow-listed AIDL. No arbitrary shell, no generic binder
  proxy, no Shizuku identity cloning.
- **Operations:** `provisionDeviceOwner`, `grantWriteSecureSettings`,
  `setAccessibilityEnabled`, `getDiagnostics`, plus state/authorization.
- **Authorization:** enforced in the server. When the user approves a
  client in the manager UI, the server records (package, UID,
  signing-certificate SHA-256) resolved by itself; every privileged call
  re-verifies all three and fails closed.
- **Startup:** the server is a `main()` launched through `app_process`
  (ADB shell UID 2000, or root UID 0 via `su`). An ordinary app cannot
  start it silently — the manager shows the exact ADB command for this
  device and offers a root start where a root manager exists. The server
  stops at reboot and the manager reports that honestly.
- **Verification:** every operation post-verifies (owner re-read, setting
  re-read, permission re-check) and decisive shell steps carry exit codes.

## Modules

- `:app` — manager UI (Material 3, Arabic + English, light/dark) plus the
  privileged server and starter, in one APK (the server's `app_process`
  classpath is this APK).
- `:client` — the Kotlin client library (AAR) Azm depends on: binder
  discovery (server push into the client's own provider, or pull from the
  manager's provider), the AIDL stubs, and the typed API.

## Build

JDK 21, Android SDK 36, Gradle 8.12, AGP 8.9.1, Kotlin 2.1.0.

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

In Ahmed's sandbox, `./build_debug.sh` wraps Gradle with the toolchain
and relay setup the sandbox needs; it is not required elsewhere.

## Status

See `IMPLEMENTATION_STATUS.md`. Device testing is pending Ahmed's phone;
nothing in this repository has been marked device-verified yet.
