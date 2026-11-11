# Azm Admin

Ahmed's own privileged-service manager for the Azm ecosystem — the
standalone replacement for the separate Shizuku app, providing exactly
the setup-time privileged operations Azm needs (Strategy B: typed,
allow-listed AIDL, server-side authorization; no generic shell, no
generic binder proxy).

- `project/` — the Android project (`:app` manager + privileged server,
  `:client` library Azm depends on). Start with `project/README.md` and
  `project/IMPLEMENTATION_STATUS.md`.
- `REVERSE_ENGINEERING.md` — what Azm actually used from Shizuku and the
  minimum replacement set (Stages 1–5).
- `SHIZUKU_ARCHITECTURE.md`, `BINDER_PROTOCOL.md` — the deep Shizuku
  server/Binder reference the design was built from.
- `AZM_ADMIN_ARCHITECTURE.md` — the approved Strategy A vs B decision.
- `SHIZUKU_AZM_COMPATIBILITY.md` — mechanism verdicts and the approved
  M1–M6 Azm change list.
- `PROGRESS.md` — project state and evidence log.

Status: implemented and build-verified (unit tests 10/10, Azm
integration analyze clean + 149/149 tests); real-device testing is
pending and nothing here is marked device-verified yet. The release APK
is unsigned — Azm Admin's dedicated signing key is a separate stage and
is never stored in this repository.
