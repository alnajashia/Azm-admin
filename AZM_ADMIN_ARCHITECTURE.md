# AZM ADMIN — Architecture decision (draft, 2026-11-11)

## Strategy A — Shizuku-API compatibility layer
Azm Admin would speak `IShizukuService` (descriptor `moe.shizuku.server.IShizukuService`, same transaction codes), deliver its binder via a provider with authority `moe.shizuku.privileged.api.shizuku`, and pass Azm's package check for `moe.shizuku.privileged.api`.
- Pro: Azm client code unchanged.
- Con: requires Azm Admin to claim Shizuku's package identity / provider authority (install conflict if real Shizuku is present; fragile, misleading identity); must replicate generic `newProcess` + generic binder-wrapper proxying — i.e. re-expose an *unrestricted* privileged interface to any app that asks, the opposite of the brief's security requirements; protocol quirks (pre-v11 paths, flags-for-uid, permission-confirmation dispatch) must all be mimicked.
- Licensing: API module is MIT, server Apache-2.0 — reuse is permitted with notices, but identity cloning is not a licensing question, it's a design smell.

## Strategy B — Custom Azm Admin client API (RECOMMENDED)
Azm Admin exposes its **own** AIDL, with **typed, allow-listed operations** instead of arbitrary shell:
- `getState()` (server running, uid, version), `requestAuthorization()` / authorization enforced **in the server** by caller UID + package + signature, Azm-only at v1.
- `provisionDeviceOwner(azmAdminComponent)` — server performs, as shell UID: user_setup_complete bypass/restore, `IDevicePolicyManager.setActiveAdmin/setDeviceOwner` directly via ServiceManager in the server process, verifies by re-reading owner state, returns typed result. No binder-wrapper reflection in the client at all.
- `grantWriteSecureSettings(package)`, `setAccessibilityEnabled(component, enabled)` (verified by re-read server-side or client-side), `diagnostics()` (accounts/users/owners, structured).
- Lifecycle: manager app (Kotlin, Material 3, AR/EN) with in-app ADB pairing (mDNS + pairing client, the manager/adb pattern from Shizuku, reimplemented under Apache-2.0 with attribution) that pushes/runs a native starter which launches the server via `app_process` (`rikka`-style `ShizukuService.main` pattern, our own class), plus optional root start via libsu. Server runs as shell (2000) under ADB start, root (0) under root start; it dies with reboot — manager shows real state via binder ping, never assumed.

Azm-side change is confined to its `shizuku/` package: `ShizukuGateway/Runner/Provisioning` are replaced by `AzmAdminGateway/Client/Provisioning` keeping the **same internal Kotlin surface** toward `PrivilegedChannelHandler`, so Flutter/Dart and the rest of Kotlin are untouched. Channel method names can stay or be renamed in one deliberate pass.

- Pro: smallest secure surface (no arbitrary shell, no arbitrary binder proxy), honest identity, no install conflict with Shizuku, simpler server (Azm needs 5 operation groups, not Shizuku's whole API — no user services, no system properties).
- Con: Azm needs that one-package change + a new release; third-party Shizuku apps can't use Azm Admin v1 (out of scope by design).

## Decision
**Strategy B — APPROVED by Ahmed, 2026-11-11.** Authorization design refined: no hardcoded signature — when the user approves a client in the manager UI, the server records (package, UID, signing-cert digest) at grant time (trust-on-first-approval); every later call must match that record exactly. This covers Azm release and debug builds without weakening the check. It is the simplest approach that fully satisfies Azm's verified requirements (Stage 5 matrix in REVERSE_ENGINEERING.md) and the brief's own security rules (§7E, §10).

## Components
1. `:server` — privileged process code (started via app_process, not an Android Service): AIDL impl, auth manager (UID/package/signature allow-list, persisted grants), typed ops, logging with redaction.
2. `:starter` — native starter (fork/exec app_process, CLASSPATH=manager APK, process name `azm_admin_server`), same pattern as upstream starter.cpp (Apache-2.0, attribution in THIRD_PARTY_LICENSES.md).
3. `:manager` — the Azm Admin app: dashboard (real binder-derived state), ADB wireless pairing + start, root start, authorized-apps screen, logs/diagnostics, AR/EN, light/dark M3.
4. `:client` — Kotlin AAR for Azm: connection management, version negotiation, death recipient + reconnect, typed API, error taxonomy mirroring Azm's existing `SHIZUKU_*` codes as `AZMADMIN_*`.
