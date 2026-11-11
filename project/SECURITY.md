# SECURITY — Azm Admin

## Boundaries
- The privileged server is a separate process running as shell (2000)
  under ADB start or root (0) under root start. It exists only after an
  explicit user action (an ADB command from a paired/authorized host, or
  a root grant). Azm Admin introduces no privilege-escalation exploit and
  does not bypass Android's security model.
- The manager app and client apps are ordinary apps. Holding the server
  binder is not a privilege: it is distributed through providers so
  clients can *ask*; the server decides.

## Authorization (server-side, fail closed)
- Identity = Binder calling UID only. The server resolves UID → packages
  and package → signing certificate itself via IPackageManager.
- A grant is recorded only by a manager-only call (`approveClient`), and
  the server resolves (package, UID, cert SHA-256) at that moment —
  caller-supplied identity values are never stored or trusted.
- Every privileged call re-verifies package, UID, and the package's
  *current* signing certificate against the record. A reinstall under a
  different key silently loses the grant.
- A client may only act on its own package: provision itself, grant
  itself WRITE_SECURE_SETTINGS, toggle its own accessibility service.
  Target mismatches are refused.
- Denial records nothing (one-time), as in Shizuku's model.
- A corrupt or unreadable grant store starts empty (fail closed).

## Surface reduction vs Shizuku
- No arbitrary shell execution for clients (Shizuku's `newProcess`).
- No generic binder-transact proxy (Shizuku's `transactRemote`, which the
  Stage 2–3 audit showed replays parcels under the server's identity with
  no target validation). Device-policy calls happen inside the server
  for one fixed purpose.
- No user services, no system-property access, no SELinux APIs — Azm
  never used them (Stage 4–5 audit).

## Data handling
- The grant store contains package names, UIDs, certificate digests and
  timestamps — no user data, no credentials. It lives in the server's
  startup directory (`/data/local/tmp/azmadmin/grants.json` by default),
  writable by the shell/root identity, not by ordinary apps.
- Logs contain package names, exit codes and operation outcomes; they
  never contain signing keys, passwords, or user content.
- Signing material is never committed to this repository.
