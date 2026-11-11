# THIRD-PARTY LICENSES

## Shizuku (RikkaApps)
Azm Admin was designed from a source-level study of Shizuku
(https://github.com/RikkaApps/Shizuku, clone HEAD `b844bc4`). No Shizuku
source code is copied into this project; the architecture patterns it
learned from — an `app_process`-launched server, binder delivery through
a ContentProvider call, an approval flow in the manager app — are
reimplemented for Azm Admin's own identity and typed protocol.

Upstream licenses, verified from the clone (Stage 2):
- Repository root `LICENSE`: Apache License 2.0 (© RikkaApps).
- `api/` submodule `LICENSE`: MIT License (© 2021 RikkaW) — the
  `BinderContainer` parcelable shape in `:client` follows the Shizuku API
  module's public pattern under this license.

AndroidX and Material Components are Apache License 2.0
(© The Android Open Source Project / Google). Kotlin is Apache License
2.0 (© JetBrains). JUnit 4 is Eclipse Public License 1.0.

Wireless-debugging start (added in v1.0.1):
- libadb-android 3.1.1 (`com.github.MuntashirAkon:libadb-android` via
  JitPack) — ADB wire protocol, SPAKE2 pairing and TLS connect:
  Apache License 2.0, © Muntashir Akon.
  https://github.com/MuntashirAkon/libadb-android
- Conscrypt Android 2.5.3 — TLS 1.3 provider for the ADB channels:
  Apache License 2.0, © The Android Open Source Project.
- BouncyCastle 1.81 (bcprov/bcpkix, jdk15to18) — self-signed ADB
  identity certificates: Bouncy Castle Licence (MIT-style),
  © The Legion of the Bouncy Castle.
