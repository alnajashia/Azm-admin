# REVERSE ENGINEERING — Shizuku & Azm's use of it
Started 2026-11-11. Status key: ✅ verified from source/tool output in this project · ⏳ pending deep dive · ❌ not available

## Stage 1 — REA ✅ (with hard limits)
- REA (Reverse Engineer Anything) `rea-agents` **6.8.0**, pinned, at `~/workspace/custom-connectors/custom.rea/`. `rea doctor` re-run 2026-11-11: Node 24.22.1 ✅, host Ubuntu 24.04 ✅.
- MCP live this session: `list-tools` + `inspect_analysis_settings` call succeeded (the only tool in the connector's allow-list).
- ❌ **No native engine installed**: Hopper, Ghidra, IDA, JADX, Kuna all "not configured" per doctor. Java is not installed. → REA **cannot decompile the Shizuku APK / native starter** here.
- Consequence (honest): Shizuku is fully open source, so source-code investigation is the primary route and loses nothing material; REA is used where it actually works and its limits are recorded, not papered over. APK-level REA analysis stays ❌ until a JADX/Ghidra engine is supplied.

## Stage 2 — Shizuku source ✅ basics / ⏳ deep diagrams
- Local clone (from the earlier Dominanter project): `~/workspace/dominater-rebuild/sources/shizuku`, HEAD `b844bc4` "fixup! Clarify license".
- Modules (`settings.gradle`): `:manager` (app), `:server`, `:starter`, `:shell`, `:common`, + `api/` submodule (Shizuku-API, at `a27f6e4`).
- **Licensing is per-module, as the brief warned**: repo root `LICENSE` = **Apache-2.0**; `api/LICENSE` = **MIT** (© 2021 RikkaW). Only two LICENSE files exist in the tree.
- Server entry: `rikka.shizuku.server.ShizukuService` (server module), launched by native `manager/src/main/jni/starter.cpp`: sets `CLASSPATH` to the manager APK, execs `app_process` with process name `shizuku_server`, main class `rikka.shizuku.server.ShizukuService`. Fatal exit codes cover set-CLASSPATH / fork / app_process / UID / pm-path / SELinux binder block.
- Starter (Java side): `moe.shizuku.starter.ServiceStarter` — also builds the per-app *user service* command (`CLASSPATH=… app_process … ServiceStarter --token=…`).
- Binder delivery: server sends its binder to the manager app through the ContentProvider `moe.shizuku.privileged.api.shizuku` (`ActivityManagerApis.getContentProviderExternal`, extra `moe.shizuku.privileged.api.intent.extra.BINDER`), retrying once after force-stopping the manager if the provider is dead.
- Wireless debugging: manager has a full in-app ADB implementation — `manager/.../adb/` `AdbClient, AdbKey, AdbMdns, AdbPairingClient, AdbPairingService, AdbProtocol` — i.e. pairing + mDNS discovery + ADB wire protocol implemented in-app, then the starter binary is pushed/run over that ADB shell connection. Root mode: same starter run via a root shell (libsu, jitpack `com.github.topjohnwu.libsu` in the version catalog).
- AIDL (`api/aidl/.../moe/shizuku/server/IShizukuService.aidl`), transaction codes are explicit: `getVersion=2, getUid=3, checkPermission=4, newProcess=7, getSELinuxContext=8, get/setSystemProperty=9/10, addUserService=11, removeUserService=12, requestPermission=14, checkSelfPermission=15, shouldShowRequestPermissionRationale=16, attachApplication=17, exit=100, attachUserService=101, dispatchPackageChanged=102, isHidden=103, dispatchPermissionConfirmationResult=104, getFlagsForUid=105, updateFlagsForUid=106`. Companion AIDLs: `IRemoteProcess`, `IShizukuApplication`, `IShizukuServiceConnection`.
- Client API (`api/api/.../rikka/shizuku/`): `Shizuku.java`, `ShizukuBinderWrapper.java`, `ShizukuRemoteProcess.java`, `ShizukuServiceConnection(s).java`, `SystemServiceHelper.java`.

## Stage 4–5 — What Azm (v1.0.6) actually uses ✅ — the dependency matrix
Inspected in the CURRENT Azm source (`~/workspace/dominater-rebuild/project`), not just the original DeChainer analysis (581-line `analysis/SHIZUKU_INTEGRATION.md`, 2026-11-09, which matches).

Azm package `io.github.alnajashia.dominanter`; Shizuku deps pinned `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`, imported **only** by the `shizuku/` provisioning package (setup-time), never by runtime enforcement. Exactly 3 files:

| Azm file | Shizuku surface used |
|---|---|
| `shizuku/ShizukuGateway.kt` | `Shizuku.pingBinder()`, `checkSelfPermission()`, `requestPermission(911)`, `getVersion()`, `getUid()`, `isPreV11()`, binder-received/dead + permission-result listeners; package check for `moe.shizuku.privileged.api` |
| `shizuku/ShizukuRunner.kt` | `Shizuku.getBinder()` → `IShizukuService.newProcess()` — shell execution in the server process (shell/root UID), with exit codes checked (Azm fix) |
| `shizuku/ShizukuProvisioning.kt` | `ShizukuRunner` shell + `ShizukuBinderWrapper` swapped into `DevicePolicyManager.mService` (reflection) so DPM calls execute as the server UID |

The complete privileged operation set (13 shell invocations + 3 binder-wrapped DPM calls):
1. `settings get/put secure user_setup_complete` (bypass 1→0, restore in finally) + `settings get global device_provisioned` + reflective `forceUpdateUserSetupComplete`
2. Binder-wrapped DPM: `setActiveAdmin`, `setDeviceOwner` (reflective, several signatures) — fallback: `dpm set-device-owner …` shell (#7)
3. `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` (#6)
4. Diagnostics: `dumpsys account` (#8), `pm list users` (#10), `dpm list-owners` (#9)
5. Accessibility: `settings get/put secure enabled_accessibility_services`, `settings put secure accessibility_enabled 1` (#11–#13), verified by re-read

Channel surface exposed to Flutter (`PrivilegedChannelHandler`): `getShizukuState`, `requestShizukuPermission`, `installShizuku` (opens Play page — goes away), `provisionDeviceOwner`, `grantWriteSecureSettings`, `setAccessibilityEnabled`, diagnostics.

**Azm uses NONE of**: user services (`addUserService`), system properties, SELinux context, hidden-API bypass for general use, or any Shizuku feature outside provisioning. Runtime app-blocking is Device Owner + Accessibility, independent of Shizuku.

## Minimum replacement set (Stage 5 conclusion)
Azm Admin must provide, at shell-UID execution context:
- **A.** An allow-listed shell-exec equivalent (or, better, dedicated typed operations for the 13 commands above).
- **B.** A way to perform the 3 DPM calls as shell UID — server-side in Azm Admin's server process (it can call `IDevicePolicyManager` directly via ServiceManager) is cleaner and safer than replicating generic `ShizukuBinderWrapper` arbitrary-binder proxying.
- **C.** State + authorization API equivalent to ping/check/request-permission, enforced server-side, restricted to Azm's package/signature.

## Delivery targets — verified 2026-11-11
- GitHub `alnajashia/Azm-admin`: **exists, public, completely EMPTY** (API: "Git Repository is empty", created 2026-11-10). Nothing to preserve; first push creates `main`. ⚠️ Public repo + the user's PUBLIC CONTENT RULE: features/benefits only in public text; no signing, backup or audit internals.
- Google Drive folder `azm admin`: **does not exist yet** (Drive search 2026-11-11, 0 results). To be created at Stage 17 only, private, for encrypted signing/release material — never in GitHub, never printed in chat.
