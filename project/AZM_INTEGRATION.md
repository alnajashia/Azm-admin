# AZM INTEGRATION — how Azm will talk to Azm Admin

Azm's change is confined to the approved M1–M6 list
(`../SHIZUKU_AZM_COMPATIBILITY.md` §4); Ahmed granted permission for
exactly that list on 2026-11-11 ("عدّل Azm حسب M1–M6 عند الدمج").

- **M1** Replace Azm's three `shizuku/` Kotlin files with Azm Admin
  client-backed equivalents keeping the same internal surface toward
  `PrivilegedChannelHandler`: state/permission flow maps to the client
  library's state + `checkAuthorization`/`requestAuthorization`;
  provisioning maps to the typed server operations (the 13 shell commands
  and 3 binder-wrapped DPM calls now execute inside the server, verified
  there). The Dart payload shapes (`getShizukuState` result, error codes)
  stay identical.
- **M2** Gradle: remove `dev.rikka.shizuku:api:13.1.5` and
  `:provider:13.1.5`; add the Azm Admin `:client` library.
- **M3** Manifest: remove the `rikka.shizuku.ShizukuProvider` declaration;
  declare the client provider (`AzmAdminProvider`, authority
  `${applicationId}.azmadmin`) so the server can push its binder into Azm
  and Azm can pull it from the manager.
- **M4** `installShizuku` flow redirects to Azm Admin (open the manager
  app / its install state); error-code names stay as-is.
- **M5** Dart texts only: user-visible "Shizuku" strings become
  "Azm Admin". No Dart logic changes.
- **M6** Setup guidance copy updated to "open Azm Admin and start its
  server"; same state machine, same 500 ms polling.

Explicitly untouched: all other Kotlin and Dart, signing, versioning,
Azm's Device Owner enforcement, Accessibility blocking, and the recovery
gate — none of them ever depended on Shizuku at runtime.
