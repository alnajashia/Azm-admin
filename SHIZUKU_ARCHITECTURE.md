# SHIZUKU ARCHITECTURE — deep reverse engineering of the local clone

Source of truth for every claim in this file: the local clone at
`~/workspace/dominater-rebuild/sources/shizuku`, HEAD `b844bc4` ("fixup! Clarify license"),
API submodule in `api/`. No web sources were used. Every factual claim is cited as
`path — Class.method/field`. Paths below are relative to the clone root unless they start
with `api/`. Companion protocol reference: `BINDER_PROTOCOL.md` in this folder.
Prior context (what Azm actually uses): `REVERSE_ENGINEERING.md` in this folder.

Conventions: "server" = the privileged `shizuku_server` process; "manager" = the
`moe.shizuku.privileged.api` app; "client" = any third-party app using the Shizuku API
(Azm included).

---

## 1. Component and process map

Four processes are involved in a running system, in three different UIDs:

| Process | How it is started | Identity | Key class |
|---|---|---|---|
| Manager app | Normal Android app launch | The manager app's own app UID | `manager/.../ShizukuApplication.kt — ShizukuApplication` |
| Privileged server | Native starter binary `libshizuku.so` execs `app_process` | UID 2000 (adb/shell) or UID 0 (root) — §4 | `server/.../ShizukuService.java — ShizukuService.main` |
| Client app | Normal Android app launch | The client's own app UID | `api/api/.../Shizuku.java — Shizuku` (static client state) |
| User-service process (optional) | Server runs a shell command that execs `app_process` again | Same UID as the server (2000 or 0) | `starter/.../ServiceStarter.java — ServiceStarter.main` |

Module responsibilities, from `settings.gradle` and the module sources as inventoried in
`REVERSE_ENGINEERING.md` (Stage 2): `:manager` (the app, incl. native starter and the
in-app ADB stack), `:server` (the privileged server implementation), `:starter`
(`moe.shizuku.starter.ServiceStarter`, the user-service process entry point), `:shell`
(the `rish` shell loader, §11), `:common`, and the `api/` submodule containing the AIDL
module, the client API (`rikka.shizuku.*`), the client ContentProvider
(`rikka.shizuku.ShizukuProvider`), `server-shared` (the abstract server base used by both
Shizuku and Sui), `shared` (`ShizukuApiConstants`), and `rish`.

The server is **not** an Android Service component and is **not** registered in
`ServiceManager` under a public name: there is no `ServiceManager.addService` call anywhere
in `ShizukuService` or the `server-shared` `Service` base. Clients obtain the server binder
only by the ContentProvider push mechanism of §6 (`server/.../ShizukuService.java —
sendBinderToUserApp`; `api/server-shared/.../Service.java` contains no registration call).
This is the single most important structural fact: discovery is push-based, not lookup-based.

## 2. The native starter (`libshizuku.so`)

### 2.1 What the starter file is

`manager/.../starter/Starter.kt — Starter`: the starter is the file
`libshizuku.so` inside the manager APK's `nativeLibraryDir`. Three command forms are built
from it:

- `Starter.userCommand` — the bare binary path (what a user pastes after `adb shell`).
- `Starter.adbCommand` — `"adb shell " + userCommand` (the instruction shown for a
  computer-driven start).
- `Starter.internalCommand` — `userCommand + " --apk=" + applicationInfo.sourceDir`,
  used when the manager itself launches the starter through a root shell or through its
  own wireless-ADB client (§5, diagrams D1–D3).

### 2.2 `starter.cpp` execution sequence

`manager/src/main/jni/starter.cpp — main`, in order:

1. Parse `--apk=<path>` out of `argv` (any position).
2. Identity gate: `getuid()` must be `0` (root) or `2000` (shell/adb); anything else
   prints `fatal: run Shizuku from non root nor adb user` and exits with
   `EXIT_FATAL_UID` (6). This check is the entire reason an ordinary app cannot start
   the server: the starter refuses to run as an app UID.
3. Root mode only (`uid == 0`): `switch_cgroup()` tries `/acct`, `/dev/cg2_bpf`,
   `/sys/fs/cgroup`, then `/dev/memcg/apps` (the last only when
   `ro.config.per_app_memcg` is set and not `"false"`); failure is a warning, not fatal.
   On API ≥ 29 root mode also calls `switch_mnt_ns(1)` to move into init's mount
   namespace (`starter.cpp — main`, `switch_cgroup`).
4. Root mode only: SELinux pre-flight. It reads its own context with `se::getcon` and
   checks `selinux_check_access("u:r:untrusted_app:s0", <own context>, "binder",
   "call")` and the same for `"transfer"` (`starter.cpp — check_selinux`). If either
   fails, the `su` implementation in use would block apps from talking to the server
   over Binder, so it exits with `EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX` (10).
   ADB mode skips this because the shell context is known-good.
5. Kill the previous server: it walks `/proc` (`foreach_proc`, `get_proc_name`) and
   `SIGKILL`s every process whose name equals `shizuku_server`, except itself. `EPERM`
   here is fatal (`EXIT_FATAL_KILL`, 9) — this is what happens when an adb-started
   server (UID 2000) is still running and a root start (or vice versa) cannot kill it;
   the message tells the user to stop the existing server from the app first.
6. Resolve the manager APK path: the `--apk=` value if readable, otherwise the output
   of `popen("pm path moe.shizuku.privileged.api")` parsed for the `package:` prefix.
   No path → `EXIT_FATAL_PM_PATH` (7). The path is re-checked with `access(R_OK)`.
7. `start_server()` forks (`starter.cpp — start_server`). The parent prints
   `info: shizuku_server pid is <pid>` and `info: shizuku_starter exit with 0` and
   exits `EXIT_SUCCESS` — that exact final line is what the manager UI waits for
   (`StarterActivity.kt`, §5.4). The child calls `setsid()`, `chdir("/")`, and redirects
   stdin/stdout/stderr to `/dev/null`, detaching the server from the launching shell so
   it survives the shell (and the adb connection) going away.
8. The child runs `run_server()` (`starter.cpp — run_server`): `setenv("CLASSPATH",
   dex_path)` (failure → `EXIT_FATAL_SET_CLASSPATH`, 3), then `execvp` of
   `/system/bin/app_process` with arguments
   `-Djava.class.path=<apk>`, `-Dshizuku.library.path=<apk dir>/lib/<ABI>`,
   `/system/bin`, `--nice-name=shizuku_server`, main class
   `rikka.shizuku.server.ShizukuService` (constants `SERVER_NAME`, `SERVER_CLASS_PATH`
   in `starter.cpp`). `execvp` failure → `EXIT_FATAL_APP_PROCESS` (5); fork failure in
   the parent → `EXIT_FATAL_FORK` (4). Debug builds additionally inject JDWP arguments
   (`ARG_PUSH_DEBUG_VM_PARAMS`, API-level dependent).

So the "server binary" is the manager APK itself used as the app_process classpath:
the same APK file supplies the manager UI (as an app), the starter (as a native lib),
and the server code (as dex on `CLASSPATH`).

## 3. Server initialization sequence

Entry: `server/.../ShizukuService.java — ShizukuService.main(String[])`:

1. `DdmHandleAppName.setAppName("shizuku_server", 0)` — names the process for DDMS.
2. `RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"))` — the
   `-Dshizuku.library.path` set by `run_server` (§2.2 step 8); used later to load
   `librish.so` (`api/rish/.../RishConfig.java — init/loadLibrary`).
3. `Looper.prepareMainLooper()`, construct `new ShizukuService()`, then `Looper.loop()`
   forever. The process has no other exit path except `System.exit` (§10).

Constructor: `ShizukuService.<init>`, in order:

1. `super()` → `api/server-shared/.../Service.java — Service.<init>`: first
   `RishConfig.init(BINDER_DESCRIPTOR, 30000)` (the rish shell sub-protocol lives on
   the same binder at transaction codes starting at 30000, §11), then creates the three
   managers via the subclass factories: `onCreateUserServiceManager()`,
   `onCreateConfigManager()`, `onCreateClientManager()`, then constructs the
   `RishService` with permission enforcement delegated back to
   `Service.enforceCallingPermission`.
2. Back in `ShizukuService.<init>`: `HandlerUtil.setMainHandler(mainHandler)` so
   `server-shared` code can post to this process's main looper.
3. Wait for core system services: `waitSystemService("package")`, then
   `Context.ACTIVITY_SERVICE`, `Context.USER_SERVICE`, `Context.APP_OPS_SERVICE`
   (`ShizukuService.waitSystemService` polls `ServiceManager.getService(name)` once per
   second until non-null). The server cannot do anything useful before `system_server`
   is up, and at boot-adjacent starts these may lag.
4. Manager presence gate: `getManagerApplicationInfo()` =
   `PackageManagerApis.getApplicationInfoNoThrow("moe.shizuku.privileged.api", 0, 0)`.
   If the manager app is not installed for user 0, `System.exit(MANAGER_APP_NOT_FOUND)`
   where `ServerConstants.MANAGER_APP_NOT_FOUND = 50`
   (`server/.../ServerConstants.java`). The manager's appId (`ai.uid`) is cached in
   `managerAppId` and is the identity used by every manager-only permission check.
5. `configManager = getConfigManager()` / `clientManager = getClientManager()` —
   note the config manager was already constructed inside `super()`; its constructor
   loads and reconciles the persisted permission config (§8).
6. APK-watch: `ApkChangedObservers.start(ai.sourceDir, …)` registers a `FileObserver`
   on the manager APK's parent directory watching for `DELETE` of `base.apk`
   (`server/.../ApkChangedObservers.kt — ApkChangedObservers.start`,
   `ApkChangedObserver.onEvent`). If the manager app disappears (uninstall/update
   replacing the file) and `getManagerApplicationInfo()` then returns null, the server
   exits with code 50 — a server whose manager (and therefore whose dex source and
   authorization UI) is gone must not linger.
7. `BinderSender.register(this)` — installs the process/UID observers that push the
   binder to clients as they start (§6).
8. `mainHandler.post(() -> { sendBinderToClient(); sendBinderToManager(); })` — the
   initial binder push to every already-installed eligible app and to the manager
   (§6.3). Posting (rather than calling inline) lets the constructor finish and the
   looper start first.

There is no step that publishes the binder to `ServiceManager`, broadcasts it, or writes
it to a file: after step 8 the server is reachable **only** by binders already pushed.

## 4. Execution context: ADB mode vs root mode

The server process keeps the identity of whoever launched the starter; nothing in
`starter.cpp` or `ShizukuService` calls `setuid`/`setgid` — the only identity code is
the admission check and the root-only cgroup/mount-namespace/SELinux work of §2.2.

| Property | ADB / wireless-debug mode | Root mode | Source |
|---|---|---|---|
| Process UID | 2000 (`AID_SHELL`) | 0 | `starter.cpp — main` admits only `uid == 0 \|\| uid == 2000` |
| Process GID / groups | Inherited from the adb shell (shell's groups); no explicit handling exists in `starter.cpp` | Inherited from the root shell; no explicit handling exists in `starter.cpp` | Absence verified by full read of `starter.cpp` |
| `getUid()` answer to clients | 2000 | 0 | `api/server-shared/.../Service.java — Service.getUid` returns `Os.getuid()` |
| SELinux context | `u:r:shell:s0` | Depends on the `su` in use, e.g. `u:r:magisk:s0` for Magisk | `api/api/.../Shizuku.java — getSELinuxContext` javadoc; value itself from `Service.getSELinuxContext` = `SELinux.getContext()` |
| Extra startup work | None | cgroup switch, mount-namespace switch (API ≥ 29), SELinux binder pre-flight | `starter.cpp — main` |
| What the identity can do | Everything the shell user can do: `cmd` services, `settings`, `pm grant`, device-policy calls reachable by shell, app_process spawning | Everything, including operations shell is denied | Consequence of the UID; the server's privileged calls are plain Binder/`Runtime.exec` calls made with this identity (§4 and §7 of `BINDER_PROTOCOL.md`) |
| Persistence across reboot | No — server dies with the device; restart needs a new authorized start (§5, §10) | No — same, except the manager's boot receiver can auto-restart root mode (§10) | `manager/.../receiver/BootCompleteReceiver.kt` |

Client-visible consequence: `Shizuku.checkRemotePermission` short-circuits to
`PERMISSION_GRANTED` for any permission when `serverUid == 0`
(`api/api/.../Shizuku.java — checkRemotePermission`), because as root the question is
meaningless; under ADB it asks the server to check the permission against the **server's**
UID (`Service.checkPermission` → `PermissionManagerApis.checkPermission(permission,
Os.getuid())`).

## 5. How the manager starts the server (manager side)

`manager/.../starter/Starter.kt — Starter.internalCommand` is the one command used by
all three manager-driven start paths; they differ only in the shell that executes it.

### 5.1 Root start

`manager/.../starter/StarterActivity.kt — ViewModel.startRoot`: obtains a libsu shell
(`Shell.getShell()`), verifies `isRoot` (one retry after closing the cached shell, then
`NotRootedException`), then runs `Shell.cmd(Starter.internalCommand)` streaming output
lines into the UI. The root shell executes `libshizuku.so --apk=<manager apk>`, i.e.
`starter.cpp` running as UID 0 (§2.2, §4).

### 5.2 Wired-ADB start (from a computer)

There is no in-app code path for a USB-attached computer: the manager displays
`Starter.adbCommand` (`"adb shell " + userCommand`) for the user to run on the computer
(`Starter.kt — adbCommand`). The device-side `adbd` shell (UID 2000) executes the
starter exactly as in §2.2.

### 5.3 Wireless-debugging start (in-app ADB client)

The manager implements the ADB wire protocol itself; no external `adb` binary is used:

- **Discovery**: `manager/.../adb/AdbMdns.kt — AdbMdns` does mDNS lookups for
  `_adb-tls-connect._tcp` (connect service) and `_adb-tls-pairing._tcp` (pairing
  service) (constants `AdbMdns.TLS_CONNECT`, `AdbMdns.TLS_PAIRING`).
- **Pairing (once per device/key)**: `manager/.../adb/AdbPairingService.kt —
  AdbPairingService` is a foreground service that watches mDNS for the pairing port,
  posts a notification with a `RemoteInput` for the 6-digit pairing code shown by
  Android's wireless-debugging settings, then runs
  `AdbPairingClient(host, port, code, key).start()` against `127.0.0.1:<pairing port>`
  (`AdbPairingService.onInput`). The key is
  `AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")`
  (`manager/.../adb/AdbKey.kt`), i.e. a manager-owned ADB keypair persisted in the
  manager's preferences. The pairing cryptography is native: `ShizukuApplication`'s
  static init does `System.loadLibrary("adb")` on API ≥ 30
  (`manager/.../ShizukuApplication.kt`), backed by `manager/src/main/jni/adb_pairing.cpp`.
- **Connect + run**: `manager/.../starter/StarterActivity.kt — ViewModel.startAdb`
  builds the same `AdbKey`, then `AdbClient(host, port, key).connect()` and
  `shellCommand(Starter.internalCommand)` (`manager/.../adb/AdbClient.kt — AdbClient`).
  `AdbClient.connect` speaks raw ADB protocol over a socket to `127.0.0.1:<connect
  port>`: sends `A_CNXN` with `A_VERSION`/`A_MAXDATA` and banner `host::`
  (`manager/.../adb/AdbProtocol.kt` for all `A_*` constants), upgrades to TLS when the
  peer answers `A_STLS` (required for wireless debugging; pre-API-29 TLS connect is
  rejected in code), otherwise falls back to `A_AUTH` signature/public-key auth with
  the manager key, and requires a final `A_CNXN`. `shellCommand` opens an
  `A_OPEN "shell:<command>"` stream and relays `A_WRTE` payload to the UI until
  `A_CLSE` (`AdbClient.shellCommand`).

### 5.4 Start confirmation

`StarterActivity.onCreate` observes the streamed output: when it ends with
`info: shizuku_starter exit with 0` (the parent-side line from `starter.cpp —
start_server`), it prints "Waiting for service…" and registers a one-shot
`Shizuku.OnBinderReceivedListener`; only when the server pushes its binder back into
the manager (§6) does it report "Service started" and auto-close after 3 s
(`StarterActivity.kt`). Success is therefore proven by a live binder, never assumed
from the starter's exit alone.

## 6. BinderSender: how clients discover the server

### 6.1 Registration and triggers

`server/.../BinderSender.java — BinderSender.register(ShizukuService)` stores the
service instance and registers two observers with ActivityManager (via the hidden-API
compat layer `rikka.hidden.compat.ActivityManagerApis`):

- A **process observer** (`BinderSender.ProcessObserver extends ProcessObserverAdapter`),
  always registered. `onForegroundActivitiesChanged(pid, uid, true)` and
  `onProcessStateChanged(pid, uid, …)` call `sendBinder(uid, pid)` the first time a
  pid is seen; `onProcessDied` removes the pid from the seen-set so a recycled pid can
  be served again.
- A **UID observer** (`BinderSender.UidObserver extends UidObserverAdapter`), only on
  API ≥ 26, registered with flags `UID_OBSERVER_GONE | UID_OBSERVER_IDLE |
  UID_OBSERVER_ACTIVE` (plus `UID_OBSERVER_CACHED` on API ≥ 27).
  `onUidActive`, `onUidCachedChanged(cached=false)` and `onUidIdle` all funnel to
  `uidStarts(uid)` → `sendBinder(uid, -1)` once per UID until `onUidGone` clears it.

### 6.2 The eligibility filter

`BinderSender.sendBinder(uid, pid)`: resolves the UID's packages
(`PackageManagerApis.getPackagesForUidNoThrow`), loads each package's requested
permissions, and:

- If a package requests `moe.shizuku.manager.permission.MANAGER`
  (`BinderSender.PERMISSION_MANAGER`) **and** that permission is actually granted to
  the caller (checked via `PermissionManagerApis.checkPermission` when `pid == -1`,
  or `ActivityManagerApis.checkPermission(pid, uid)` otherwise), the binder is sent
  to the **manager**: `ShizukuService.sendBinderToManger(service, userId)`.
- Else if a package requests `moe.shizuku.manager.permission.API_V23`
  (`BinderSender.PERMISSION`; same string as `ServerConstants.PERMISSION`), the binder
  is sent to that app: `ShizukuService.sendBinderToUserApp(service, packageName, userId)`.
- Otherwise nothing is sent. Requesting the API permission in the manifest is the
  discovery opt-in; merely being installed is not enough (except for the one-time
  initial sweep in §6.3, which applies the same filter).

`userId` is derived arithmetically as `uid / 100000` in `BinderSender.sendBinder`.

### 6.3 The push itself

`server/.../ShizukuService.java — sendBinderToUserApp(binder, packageName, userId,
retry)`:

1. Temporarily battery-whitelists the target for 30 s:
   `DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30_000, userId,
   316, "shell")` (316 = `PowerExemptionManager.REASON_SHELL`), so Doze cannot kill
   the target before it processes the binder. Failures are logged, not fatal.
2. Resolves the target's provider by authority `"<packageName>.shizuku"` through
   `ActivityManagerApis.getContentProviderExternal(name, userId, null, name)` — run
   from the server process, this can reach providers in apps that are not running and
   starts their process. The token argument is deliberately `null`; the in-code comment
   explains `BinderProxy.hashCode()` makes a real token unusable with
   `removeContentProviderExternal`.
3. If the provider binder is dead (`!pingBinder()`), it force-stops the target package
   and retries exactly once after 1 s — the comment attributes the dead-provider state
   to an unknown cause that a restart cures.
4. Calls the provider: `IContentProviderUtils.callCompat(provider, …, "sendBinder", …,
   extra)` where `extra` maps `"moe.shizuku.privileged.api.intent.extra.BINDER"` to
   `new BinderContainer(binder)` (`moe.shizuku.api.BinderContainer`, a Parcelable
   wrapper around an `IBinder`). A non-null reply bundle is logged as success.
5. `finally`: `ActivityManagerApis.removeContentProviderExternal(name, token)`
   releases the provider reference.

Receiver side: `api/provider/.../ShizukuProvider.java — ShizukuProvider.call /
handleSendBinder`. Method `"sendBinder"` extracts the `BinderContainer` and calls
`Shizuku.onBinderReceived(binder, ownPackageName)` (§7). If a live binder is already
held, the push is ignored (`pingBinder()` check). With multi-process support enabled,
the provider process additionally broadcasts `moe.shizuku.api.action.BINDER_RECEIVED`
in-package, and non-provider processes can pull the binder with provider method
`"getBinder"` (`ShizukuProvider.handleGetBinder`,
`requestBinderForNonProviderProcess`). The provider must be declared `exported=true`,
`multiprocess=false`, guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`
(enforced in `ShizukuProvider.attachInfo` and the class javadoc) — a signature/system
permission shell holds but normal apps do not, which is what stops arbitrary apps from
injecting a fake server binder into a client.

The manager's own provider is a subclass:
`manager/.../ShizukuManagerProvider.kt — ShizukuManagerProvider` adds the
`"sendUserService"` method used by user services (§9) and disables Sui auto-init.

### 6.4 Initial sweep

`ShizukuService.sendBinderToClient()` iterates all user ids
(`UserManagerApis.getUserIdsNoThrow`), lists installed packages with
`GET_PERMISSIONS`, and calls the same `sendBinderToUserApp` for every package that
requests `ServerConstants.PERMISSION`; `sendBinderToManager()` does the same for the
manager package in every user (`ShizukuService.sendBinderToClient/sendBinderToManager`).
This covers apps installed and running before the server started; BinderSender (§6.1)
covers everything that starts afterwards.

## 7. Client registration (`attachApplication`)

Receiving the binder is not registration. The client library completes registration
immediately, in `api/api/.../Shizuku.java — Shizuku.onBinderReceived`:

1. Stores the binder, wraps it as `IShizukuService.Stub.asInterface(binder)`, and
   `linkToDeath(DEATH_RECIPIENT)` (§10).
2. Calls `attachApplicationV13(binder, packageName)`: a hand-marshalled transaction
   (wire code 18, see `BINDER_PROTOCOL.md` §5) carrying the client's
   `IShizukuApplication` stub (`Shizuku.SHIZUKU_APPLICATION`) and a `Bundle` with
   `shizuku:attach-package-name` and `shizuku:attach-api-version = SERVER_VERSION (13)`.
   If the transact itself fails it falls back to `attachApplicationV11` (legacy raw
   code 14, binder + plain package-name string); if both fail the client marks itself
   `preV11` and considers the binder ready without a reply.

Server side, `server/.../ShizukuService.java — ShizukuService.attachApplication`:

1. Null `application`/`args` or missing package name → silent return.
2. **Identity proof**: `PackageManagerApis.getPackagesForUidNoThrow(callingUid)` must
   contain the claimed package name, else `SecurityException("Request package … does
   not belong to uid …")`. A client cannot register under another app's name; the
   Binder calling UID is the identity, the claimed name is only a label that must
   match it.
3. If no `ClientRecord` exists for `(callingUid, callingPid)`, creates one under
   `synchronized (this)` via `clientManager.addClient(uid, pid, application,
   packageName, apiVersion)` (`api/server-shared/.../ClientManager.java —
   ClientManager.addClient`): the record starts `allowed=false`, is immediately
   upgraded to `allowed=true` if the persisted config already allows that UID
   (`configManager.find(uid).isAllowed()`), and the server links a death recipient to
   the client's `IShizukuApplication` binder that removes the record when the client
   process dies. `linkToDeath` failure aborts registration (returns null).
4. Builds the reply `Bundle` (§5 of `BINDER_PROTOCOL.md`): server UID
   (`OsUtils.getUid()`), server version, patch version, SELinux context
   (`OsUtils.getSELinuxContext()`); for non-manager clients also
   `permission-granted = clientRecord.allowed` and
   `should-show-request-permission-rationale = false` (always false here — rationale
   state is computed on demand by `shouldShowRequestPermissionRationale`). One
   compatibility hack: clients that attached without an API version (`apiVersion ==
   -1`, i.e. API library 12.2.0) are told the server version is **12**, because their
   `ShizukuBinderWrapper` already speaks the v13 transact format while their attach
   path does not, and a truthful "13" would break their `transactRemote` negotiation
   (in-code comment, `ShizukuService.attachApplication`).
5. Manager special case: if the package is the manager itself, no permission fields
   are added; instead the server grants the manager the runtime permission
   `android.permission.WRITE_SECURE_SETTINGS` for that user
   (`PermissionManagerApis.grantRuntimePermission`) — this is what later lets the
   manager toggle wireless debugging at boot (§10).
6. Delivers the reply by calling back `application.bindApplication(reply)`
   (oneway AIDL, `IShizukuApplication.bindApplication`). Client side, that callback
   fills `Shizuku`'s static server-uid/version/patch/context/permission fields and
   fires the binder-received listeners (`Shizuku.SHIZUKU_APPLICATION.bindApplication`)
   — only now is the client "ready".

`api/server-shared/.../ClientRecord.java — ClientRecord` is the per-process record:
final `uid`, `pid`, `client` (`IShizukuApplication`), `packageName`, `apiVersion`,
and the mutable `allowed` flag that every permission gate consults.

## 8. Authorization

### 8.1 The gate every privileged call passes

`api/server-shared/.../Service.java — Service.enforceCallingPermission(func)`, called
at the top of `getVersion`, `getUid`, `checkPermission`, `getSELinuxContext`,
`get/setSystemProperty`, `add/removeUserService`, `newProcess` and `transactRemote`:

1. Caller UID equals the server's own UID → allowed (server-internal calls).
2. Else `ShizukuService.checkCallerPermission` (`server/.../ShizukuService.java`):
   allowed if the caller's appId equals `managerAppId` (the manager is implicitly
   trusted), or — only when the caller has **no** `ClientRecord` — if the caller
   holds the runtime permission `moe.shizuku.manager.permission.API_V23` per
   `ActivityManagerApis.checkPermission(PERMISSION, callingPid, callingUid)`. This
   second branch is the pre-attach/legacy path.
3. Else if there is no `ClientRecord` → `SecurityException("… is not an attached
   client")`.
4. Else if `!clientRecord.allowed` → `SecurityException("… requires permission")`.

So for a normal attached client, the in-memory `allowed` flag — set from persisted
config at attach (§7) or by a permission decision (§8.2) — is the whole gate.
`checkSelfPermission` and `shouldShowRequestPermissionRationale` do **not** use
`enforceCallingPermission`; they use `clientManager.requireClient(uid, pid)` (throws
`IllegalStateException("Not an attached client")`) and read `record.allowed` /
the config's denied flag (`Service.checkSelfPermission`,
`Service.shouldShowRequestPermissionRationale`).

Manager-only operations use the stricter `Service.enforceManagerPermission` →
`ShizukuService.checkCallerManagerPermission` (appId equality with `managerAppId`;
same-process calls bypass): `exit()` and `attachUserService()` in
`ShizukuService`, and `getFlagsForUid`/`updateFlagsForUid` hand-check the same
condition and silently no-op/return 0 for anyone else
(`ShizukuService.getFlagsForUid/updateFlagsForUid`).

### 8.2 The requestPermission round trip

Client call chain: app → `Shizuku.requestPermission(requestCode)` →
`IShizukuService.requestPermission` → `api/server-shared/.../Service.java —
Service.requestPermission`:

1. Server-process self-call → return. Unattached caller → `requireClient` throws.
2. `clientRecord.allowed` already true → immediately
   `clientRecord.dispatchRequestPermissionResult(requestCode, true)`; no UI.
3. Persisted config says denied (`configManager.find(uid).isDenied()`) → immediately
   dispatch `false`; no UI. (This is why a denied app must be re-enabled from the
   manager's app list, not by re-asking.)
4. Otherwise → subclass hook `ShizukuService.showPermissionConfirmation(requestCode,
   clientRecord, callingUid, callingPid, userId)`.

`ShizukuService.showPermissionConfirmation`:

1. Looks up the requester's `ApplicationInfo`; missing → return (no result is sent).
2. Work-profile nuance: if the manager package is not visible in the requester's
   user **and** the user is not a managed profile, it revokes by dispatching `false`
   and returns — there is nobody who could show UI. In a work profile it proceeds
   and starts the activity in user 0.
3. Starts the manager UI with an explicit intent: action
   `moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION`
   (`ServerConstants.REQUEST_PERMISSION_ACTION`), package = manager, flags
   `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`, extras `uid`, `pid`,
   `requestCode`, `applicationInfo`, via `ActivityManagerApis.startActivityNoThrow`.

Manager UI: `manager/.../authorization/RequestPermissionActivity.kt —
RequestPermissionActivity`:

1. `waitForBinder()` — the activity itself is a Shizuku client of its own server and
   waits up to 5 s for the binder before doing anything.
2. Sanity: missing `uid`/`pid`/`applicationInfo` extras → finish silently.
3. ADB-limitation pre-check `checkSelfPermission()`: if the **server** lacks
   `android.permission.GRANT_RUNTIME_PERMISSIONS`
   (`Shizuku.checkRemotePermission(...)` — true in root mode, and in ADB mode only
   when adbd was started with the extra grants), the user is shown the "adb is
   limited" dialog and the request is answered `allowed=false, onetime=true` — a
   grant the server could not mirror into the runtime-permission system (§8.3) must
   not be promised.
4. Otherwise a non-cancelable confirmation dialog (app label + permission-group
   description) with exactly two wired outcomes in the layout binding: **Allow**
   (`button1`) → `setResult(allowed=true, onetime=false)`; **Deny**
   (`button3`) → `setResult(allowed=false, onetime=true)`. Note the asymmetry:
   Allow is persistent, Deny from this dialog is one-time. A persistent Deny is only
   produced by the manager's app-management screen (§8.4).
5. `setResult` builds a `Bundle` with `shizuku:request-permission-reply-allowed` and
   `shizuku:request-permission-reply-is-onetime` and calls the client-API static
   `Shizuku.dispatchPermissionConfirmationResult(uid, pid, requestCode, data)`.

Server return path: `ShizukuService.dispatchPermissionConfirmationResult` — itself
manager-gated (caller appId must equal `managerAppId`, checked against
`Binder.getCallingUid()`, others are logged and ignored):

1. Reads `allowed` / `onetime` from the bundle.
2. Finds **all** `ClientRecord`s for the UID (`clientManager.findClients(uid)` —
   permission is per-UID, not per-process), sets `record.allowed = allowed` on each,
   and calls `record.dispatchRequestPermissionResult(requestCode, allowed)` only on
   the record whose pid matches the original requester. Client side, that reaches
   `IShizukuApplication.dispatchRequestPermissionResult` → the app's
   `OnRequestPermissionResultListener`s with `PERMISSION_GRANTED`/`PERMISSION_DENIED`
   (`Shizuku.SHIZUKU_APPLICATION.dispatchRequestPermissionResult`,
   `ClientRecord.dispatchRequestPermissionResult` builds the reply bundle with key
   `shizuku:request-permission-reply-allowed`).
3. If not one-time: persists the decision —
   `configManager.update(uid, packages, ConfigManager.MASK_PERMISSION,
   allowed ? FLAG_ALLOWED : FLAG_DENIED)` (§8.3).
4. If not one-time **and** allowed: mirrors the decision into Android's runtime
   permissions — for every package of that UID that declares
   `moe.shizuku.manager.permission.API_V23`, `PermissionManagerApis.
   grantRuntimePermission(packageName, PERMISSION, userId)` (the `else revoke` branch
   inside this block is dead code as written: it sits under `if (allowed)`). This
   mirroring is what makes the legacy/no-record branch of §8.1 step 2 and the config
   reconciliation of §8.3 work.

### 8.3 Config persistence

`server/.../ShizukuConfigManager.java — ShizukuConfigManager` +
`server/.../ShizukuConfig.java — ShizukuConfig`:

- **Location**: `/data/user_de/0/com.android.shell/shizuku.json` — device-encrypted
  storage of the **shell** user. This path is itself a mode artifact: it is writable
  because the server runs as shell/root, and it is why config survives server
  restarts but is tied to user 0's shell data. Access goes through
  `android.util.AtomicFile` (`ATOMIC_FILE`) for crash-safe replacement.
- **Format**: Gson JSON, `ShizukuConfig.LATEST_VERSION = 2`, a list of
  `PackageEntry{uid, flags, packages[]}` (`ShizukuConfig.PackageEntry extends
  ConfigPackageEntry`; `isAllowed()` = `flags & FLAG_ALLOWED`, `isDenied()` =
  `flags & FLAG_DENIED`; `api/server-shared/.../ConfigManager.java` defines
  `FLAG_ALLOWED = 1<<1`, `FLAG_DENIED = 1<<2`, `MASK_PERMISSION = FLAG_ALLOWED |
  FLAG_DENIED`).
- **Writes are batched**: `update()`/`remove()` mutate in memory under lock and call
  `scheduleWriteLocked()`, which posts the actual `write(config)` on a worker handler
  with a 10 s delay (`WRITE_DELAY`), coalescing bursts. A server killed inside the
  window can lose the newest decision — acceptable upstream, worth knowing.
- **Load-time reconciliation** (constructor): drops entries whose UID no longer has
  any packages; drops entries whose recorded package set no longer intersects the
  UID's current packages (a UID recycled by a different app must not inherit the
  grant); de-duplicates package lists; then re-derives permission state from the
  runtime-permission system — for every installed package requesting
  `ServerConstants.PERMISSION`, it reads `PermissionManagerApis.checkPermission`
  and writes `FLAG_ALLOWED` or `0` back into the config. The runtime permission is
  therefore a second source of truth that can resurrect/erase grants at load.

### 8.4 Management-screen grants and revocation

`manager/.../authorization/AuthorizationManager.kt — AuthorizationManager` is how the
manager's app list reads and changes state without a client request:

- List: raw binder transaction `BINDER_TRANSACTION_getApplications` (10001) →
  `ShizukuService.getApplications(userId)` returns a `ParcelableListSlice<PackageInfo>`
  of apps that either have a non-zero persisted permission flag or declare
  `moe.shizuku.client.V3_SUPPORT` metadata + the API permission.
- Grant: `Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)`;
  Revoke: `updateFlagsForUid(uid, MASK_PERMISSION, 0)`.

Server side `ShizukuService.updateFlagsForUid` (manager-only, §8.1): for a permission
mask change it sets `record.allowed` on all live records of the UID; on **revoke** it
additionally `forceStopPackageNoThrow`s each affected package and calls
`onPermissionRevoked` → `userServiceManager.removeUserServicesForPackage(packageName)`
— revocation is enforced by killing the client and its user services, not by trusting
it to stop calling. It then mirrors grant/revoke into runtime permissions per package
(both branches live here) and persists via `configManager.update(uid, null, mask,
value)`. `getFlagsForUid` reads the config entry, falling back to the runtime
permission when no entry exists (`ShizukuService.getFlagsForUidInternal`).

## 9. User services

Azm does not use user services (see `REVERSE_ENGINEERING.md` Stage 5); this section is
for completeness of the architecture record.

Concept: a client can have a component from **its own APK** executed in a fresh
`app_process` running with the server's UID, exposing an arbitrary AIDL binder back to
the client. Lifecycle (`api/server-shared/.../UserServiceManager.java —
UserServiceManager`, `UserServiceRecord.java — UserServiceRecord`,
`server/.../ShizukuUserServiceManager.java — ShizukuUserServiceManager`):

1. Client: `Shizuku.bindUserService(args, conn)` → `IShizukuService.addUserService(
   conn, argsBundle)` → `Service.addUserService` (permission-gated, §8.1) →
   `UserServiceManager.addUserService`. The bundle keys are the
   `USER_SERVICE_ARG_*` constants of `ShizukuApiConstants` (component, tag,
   version-code, debuggable, daemon, process-name suffix, 32-bit flag, no-create).
2. The manager keys records by `"<package>:<tag|className>"`, verifies the component's
   package belongs to the calling appId (`ensureCallingPackageForUserService`), and
   reuses a live record when version code matches and the binder pings. `noCreate`
   (peek) returns the version code (API ≥ 13), `-1`/`1` for not-running depending on
   client API level.
3. Starting: a 30 s starting timeout is armed (`UserServiceRecord.setStartingTimeout`),
   and on a single-thread executor `startUserService` runs `Runtime.exec("sh")` and
   pipes in the command built by `ShizukuUserServiceManager.getUserServiceStartCmd` →
   `starter/.../ServiceStarter.java — ServiceStarter.commandForUserService`:
   `(CLASSPATH='<manager apk>' /system/bin/app_process[32] [--debug…] /system/bin
   --nice-name='<pkg>:<suffix>' moe.shizuku.starter.ServiceStarter --token='<uuid-ts>'
   --package='…' --class='…' --uid=<callingUid>[ --debug-name=…])&`.
   The `CLASSPATH` value is the **manager APK** (`getUserServiceStartCmd` passes
   `ShizukuService.getManagerApplicationInfo().sourceDir` as the `managerApkPath`
   parameter of `commandForUserService`), because `ServiceStarter` itself ships in
   the manager APK; the client's service class is loaded afterwards from a package
   context built for the client's own APK (`UserService.create`, step 4). The token
   is `UUID.randomUUID() + "-" + currentTimeMillis` (`UserServiceRecord.<init>`).
4. In the new process, `ServiceStarter.main` → `api/server-shared/.../UserService.java
   — UserService.create(args)`: parses `--token/--package/--class/--uid/--debug-name`,
   builds a real package `Context` for the client's package as that user
   (`ActivityThread.systemMain().getSystemContext().createPackageContextAsUser`),
   forces `makeApplication`, loads the service class from the client's classloader and
   instantiates it (preferring a `Context` constructor). The result is an `IBinder`
   (the service object itself) plus the token.
5. Hand-back: `ServiceStarter.sendBinder` pushes that binder **to the manager app's
   provider** (`moe.shizuku.privileged.api.shizuku`, method `"sendUserService"`,
   extras = binder container + `shizuku:user-service-arg-token`), reusing the §6.3
   provider machinery including the force-stop-and-retry-once behaviour.
   `ShizukuManagerProvider.call` (manager side) waits (≤ 5 s) for the manager's own
   server binder, then calls `Shizuku.attachUserService(binder, {token})` and returns
   the **server** binder to the user-service process as the reply.
6. Server: `ShizukuService.attachUserService` (manager-only) →
   `UserServiceManager.attachUserService` → matches the record by token →
   `record.setBinder(binder)`: clears the starting timeout, `linkToDeath` (death →
   `removeSelf()`), and `broadcastBinderReceived()` → each registered
   `IShizukuServiceConnection.connected(service)` → client-side
   `ShizukuServiceConnection.connected` posts `ServiceConnection.onServiceConnected`
   on the main thread (`api/api/.../ShizukuServiceConnection.java`).
7. The user-service process links a death recipient to the **server** binder it got
   in the reply: if the server dies, it `System.exit(0)`s (`ServiceStarter.sendBinder`).
   User services never outlive the server.
8. Teardown: `removeUserService` → `record.destroy()` sends the service binder the
   transaction `USER_SERVICE_TRANSACTION_destroy` (16777115, oneway) with the
   service's own interface token — the service's destroy handler is expected to clean
   up and exit (`UserServiceRecord.destroy`, `ShizukuApiConstants`). Non-daemon
   records are also removed automatically when the last connection callback dies
   (`UserServiceRecord.ConnectionList.onCallbackDied`); daemon records (the default)
   survive client death until explicitly removed, revoked (§8.4), or the server dies.
   APK changes for the client's package are watched per record
   (`ShizukuUserServiceManager.onUserServiceRecordCreated` + `ApkChangedObservers`):
   upgrade re-arms the watch on the new path, uninstall removes the record.

## 10. Exit, restart, and recovery

There is **no in-server restart and no watchdog**: recovery is always "something
external starts the starter again".

- **Explicit exit**: `IShizukuService.exit()` → `ShizukuService.exit()` —
  manager-only (`enforceManagerPermission("exit")`), logs and `System.exit(0)`.
  The manager UI's stop action uses the client-API `Shizuku.exit()`
  (`api/api/.../Shizuku.java — exit`, library-restricted).
- **Restart = exit + start**: because `starter.cpp` kills any existing
  `shizuku_server` before starting (§2.2 step 5), running any start path again is
  the restart procedure; the manager's restart button composes exit + the selected
  start path.
- **Manager uninstalled/updated**: the APK watch (§3 step 6) exits the server with
  code 50 when the manager vanishes. (An update that replaces `base.apk` also trips
  the DELETE watch; the callback re-checks presence and only exits if the manager is
  actually gone.)
- **Server death, client view**: the client's binder death recipient fires
  (`Shizuku.DEATH_RECIPIENT`): static state is cleared (`binder`, `service`,
  server uid/version/context), `binderReady=false`, and dead listeners run
  (`Shizuku.onBinderReceived(null, null)` → `scheduleBinderDeadListeners`). When a
  new server later pushes a binder, `onBinderReceived` re-attaches (§7) and received
  listeners fire again — clients are expected to re-acquire everything, including
  re-binding user services (whose processes died with the old server, §9 step 7).
- **Client death, server view**: the `ClientRecord` is removed by the death
  recipient registered in `ClientManager.addClient`; `newProcess` children owned by
  that client are destroyed via the token mechanism described in
  `BINDER_PROTOCOL.md` §7; non-daemon user services are removed (§9 step 8).
- **Boot**: the server never self-starts. `manager/.../receiver/
  BootCompleteReceiver.kt — BootCompleteReceiver` (on `LOCKED_BOOT_COMPLETED` /
  `BOOT_COMPLETED`, user 0 only, skipped if a binder is already alive) can restart it
  only if the last launch mode was: **root** → `Shell.cmd(Starter.internalCommand)`
  through libsu; or **ADB** → only on API ≥ 33 (Tiramisu) **and** only if the manager
  holds `WRITE_SECURE_SETTINGS` (granted by the server at manager attach, §7 step 5):
  it sets `adb_wifi_enabled=1`, `adb_enabled=1`, `adb_allowed_connection_time=0` via
  `Settings.Global`, discovers the connect port with `AdbMdns(TLS_CONNECT)`, and runs
  the internal command through `AdbClient` within a 3 s latch window. In all other
  cases it logs "No support start on boot" — i.e. plain wired-ADB starts are never
  auto-recovered.
- **Failed starts** surface as starter exit codes (§2.2) in the starter UI, or as
  ADB-layer errors mapped to dialogs in `StarterActivity` (`AdbKeyException`,
  `NotRootedException`, `ConnectException`, `SSLProtocolException` → "pairing
  required").

## 11. The rish shell (`:shell` + `rish`) — secondary surface

For completeness: the same server binder also carries the **rish** interactive-shell
sub-protocol. `Service.<init>` calls `RishConfig.init(BINDER_DESCRIPTOR, 30000)`
(`api/rish/.../RishConfig.java`): sub-transactions are `30000 + {0=createHost,
1=setWindowSize, 2=getExitCode}`, dispatched by `RishService.onTransact` from
`Service.onTransact` after the transact/legacy-attach codes
(`api/server-shared/.../Service.java — onTransact`). The client half is loaded by
`shell/.../ShizukuShellLoader.java — ShizukuShellLoader.main`: a shell-launched
`app_process` that asks the manager (broadcast `rikka.shizuku.intent.action.
REQUEST_BINDER` via ActivityManager, handled by the manager's shell request handler)
for the server binder, then dex-loads `moe.shizuku.manager.shell.Shell.main` from the
manager APK. Azm does not use rish; it is recorded here only so the 30000-range codes
in `BINDER_PROTOCOL.md` are not mistaken for part of the core API.

## 12. Sequence diagrams

All diagrams are derived from the code paths cited in §§2–10. `==>` is a Binder call,
`-->` a local/intent/shell step, `[…]` an internal state change.

### D1 — ADB startup (wired, from a computer)

```
User (PC)          adbd (uid 2000)        starter libshizuku.so     app_process        ShizukuService        Manager app
   |                     |                        |                      |                     |                    |
   | adb shell <starter> |                        |                      |                     |                    |
   |-------------------->| exec as uid 2000       |                      |                     |                    |
   |                     |----------------------->| main(): uid==2000 ok |                     |                    |
   |                     |                        | kill old shizuku_server (scan /proc, SIGKILL)               |
   |                     |                        | pm path moe.shizuku.privileged.api -> apk path              |
   |                     |                        | fork: parent prints "shizuku_starter exit with 0", exits      |
   |                     |                        | child: setsid, chdir /, stdio -> /dev/null                    |
   |                     |                        | setenv CLASSPATH=<apk>; execvp app_process ---------------->|
   |                     |                        |                      | main(): Looper, new ShizukuService()  |
   |                     |                        |                      | waitSystemService(package/activity/…)|
   |                     |                        |                      | manager ai==null? -> exit(50)        |
   |                     |                        |                      | BinderSender.register; post push ---->| (see D5)
```

### D2 — Wireless-debugging startup (pair once, then connect)

```
Manager UI          AdbPairingService        Android WADB settings        AdbClient (manager)     starter/server
    |                      |                          |                          |                       |
    | start pairing        | mDNS watch               |                          |                       |
    |--------------------->| _adb-tls-pairing._tcp    |                          |                       |
    |                      |<---- port announced -----| (user enables pairing,   |                       |
    |  notification: enter 6-digit code               |  code shown here)        |                       |
    |--------------------->| AdbPairingClient(127.0.0.1, pairPort, code, AdbKey).start()  [native SPAKE via libadb]
    |                      |------- pair handshake ---------------------------->| (adbd pairing endpoint)
    |                      | success notification; key now trusted by adbd      |                       |
    |                      |                          |                          |                       |
    | Start (wireless)     |                          | mDNS _adb-tls-connect._tcp -> connect port       |
    |-------------------------------------------------------------------------->| A_CNXN -> A_STLS -> TLS (AdbKey)
    |                      |                          |                          | A_OPEN shell:<internalCommand>
    |                      |                          |                          |---------------------->| starter runs as uid 2000 (= D1 from fork on)
    |  output ends "shizuku_starter exit with 0" -> wait for binder push (D5) -> "Service started"        |
```

(Pairing internals are implemented by `AdbPairingClient.kt` + `jni/adb_pairing.cpp`;
only the service-level flow above was verified line-by-line — see the verification
notes in `BINDER_PROTOCOL.md` §10.)

### D3 — Root startup

```
Manager UI (StarterActivity)     libsu Shell (uid 0)        starter              server
    |                                  |                      |                    |
    | Shell.getShell().isRoot? ------->| (su prompt if needed)|                    |
    | Shell.cmd(internalCommand) ----->| exec as uid 0        |                    |
    |                                  |--------------------->| uid==0: switch_cgroup,
    |                                  |                      | switch_mnt_ns (API>=29),
    |                                  |                      | SELinux binder pre-flight
    |                                  |                      |   fail -> exit(10)
    |                                  |                      | kill old server; fork;
    |                                  |                      | app_process (uid 0) -->| init as D1
    |<--------- streamed stdout -------| "info: shizuku_starter exit with 0"        |
    | wait for binder push (D5) -> "Service started"                                 |
```

### D4 — Server initialization (detail of D1's last steps)

```
ShizukuService.main
  | DdmHandleAppName.setAppName("shizuku_server"); RishConfig.setLibraryPath(-Dshizuku.library.path)
  | Looper.prepareMainLooper()
  | new ShizukuService()
  |   super(): RishConfig.init(descriptor, 30000); create UserServiceMgr / ConfigMgr(load+reconcile §8.3) / ClientMgr; RishService
  |   waitSystemService: package, activity, user, appops (poll 1s)
  |   getManagerApplicationInfo() == null -> System.exit(50)
  |   ApkChangedObservers.start(manager sourceDir)   [exit(50) later if manager uninstalled]
  |   BinderSender.register(this)                    [process + uid observers]
  |   mainHandler.post(sendBinderToClient + sendBinderToManager)
  | Looper.loop()
```

### D5 — Binder registration / discovery (server push → client attach)

```
Server                          AMS / provider infra              Client app (ShizukuProvider)        Client code
  | trigger: server start sweep, ProcessObserver, or UidObserver (§6.1)                                  |
  | eligibility: package requests API_V23 (or MANAGER+granted for manager) (§6.2)                          |
  | addPowerSaveTempWhitelistApp(pkg, 30s)                                                               |
  | getContentProviderExternal("<pkg>.shizuku") ----------------->| provider process starts if needed     |
  | provider dead? -> forceStopPackage, sleep 1s, retry once                                               |
  | call(provider, "sendBinder", {BINDER: BinderContainer(serverBinder)}) ------------------------------>|
  | removeContentProviderExternal                                 | handleSendBinder:                     |
  |                                                               |   Shizuku.onBinderReceived(binder,pkg)|
  |                                                               |     linkToDeath; attachApplication (D6)|
  |<================================================================ bindApplication(reply) (§7 step 6)   |
  |                                                               |     [static state set; binderReady]   |--> OnBinderReceivedListener fires
```

### D6 — Client connection / attachApplication

```
Client (Shizuku.java)                                   Server (ShizukuService.attachApplication)
  | transact code 18: token, IShizukuApplication binder, int(1), Bundle{pkg, apiVersion=13}
  |---------------------------------------------------->| getPackagesForUid(callingUid) contains pkg? no -> SecurityException
  |                                                       | findClient(uid,pid)==null -> addClient():
  |                                                       |   record.allowed = config.find(uid).isAllowed()
  |                                                       |   linkToDeath(client binder) -> remove record on death
  |                                                       | reply Bundle{uid, version(13; 12 if apiVersion==-1),
  |                                                       |   patch, secontext, permission-granted (non-manager)}
  |<===================================================| IShizukuApplication.bindApplication(reply) [oneway]
  | [serverUid/version/context cached; permissionGranted cached; listeners fired]
```

### D7 — Authorization (requestPermission)

```
Client app            Server (Service.requestPermission)     Server (ShizukuService)        Manager UI (RequestPermissionActivity)     Config file
  | requestPermission(code)                                    |                                  |                                       |
  |====================>| requireClient; allowed? --yes------>| dispatch result(true) directly   |                                       |
  |                     | config denied? --yes--------------->| dispatch result(false) directly  |                                       |
  |                     | no                                    | showPermissionConfirmation:    |                                       |
  |                     |                                       | startActivity REQUEST_PERMISSION
  |                     |                                       |   {uid,pid,code,applicationInfo} ---------------->| waitForBinder (<=5s)
  |                     |                                       |                                  | server has GRANT_RUNTIME_PERMISSIONS?
  |                     |                                       |                                  |   no -> dialog; answer (false, onetime)
  |                     |                                       |                                  |   yes -> confirmation dialog
  |                     |                                       |                                  | user: Allow -> (true, persistent)
  |                     |                                       |                                  |       Deny  -> (false, onetime)
  |                     |                                       |<---------------------------------| Shizuku.dispatchPermissionConfirmationResult
  |                     | dispatchPermissionConfirmationResult: caller must be manager (appId check)
  |                     | all records of uid: allowed=allowed; matching pid: dispatchRequestPermissionResult(code, allowed)
  |<====================| IShizukuApplication.dispatchRequestPermissionResult(code, {allowed})
  | [OnRequestPermissionResultListener(code, GRANTED/DENIED)]
  |                     | if !onetime: configManager.update(uid, pkgs, MASK_PERMISSION, ALLOWED|DENIED) ---------------------------->| JSON write (10s batched)
  |                     | if !onetime && allowed: grantRuntimePermission(API_V23) to uid's packages
```

### D8 — Privileged invocation, path 1: `newProcess`

```
Client app                 Server (Service.newProcess)              Child process (server UID)
  | IShizukuService.newProcess(cmd[], env[], dir)                      |
  |========================>| enforceCallingPermission("newProcess")   |
  |                         | Runtime.getRuntime().exec(cmd, env, dir) |---- fork/exec as uid 2000/0
  |                         | RemoteProcessHolder(process, token=client binder)
  |                         |   linkToDeath(client binder) -> destroy() child if client dies
  |<========================| IRemoteProcess binder                    |
  | getInputStream()/getErrorStream()/getOutputStream() ==> PFD pipes to child stdout/stderr/stdin
  | waitFor() ========================> process.waitFor() -> exit code |
```

### D9 — Privileged invocation, path 2: binder wrapper (`transactRemote`)

Full parcel-level detail in `BINDER_PROTOCOL.md` §4.

```
Client app                ShizukuBinderWrapper          Server binder (code 1)         Target system service
  | e.g. IDevicePolicyManager proxy call                   |                                 |
  | transact(code, data{targetToken+args}, reply, flags)    |                                 |
  |------------------------>| rebuild parcel:              |                                 |
  |                         |  {IShizukuService token,     |                                 |
  |                         |   originalBinder, code,      |                                 |
  |                         |   [flags if server>=13],     |                                 |
  |                         |   original data bytes}       |                                 |
  |                         | transact(1, newData, reply,0)|                                 |
  |                         | ---------------------------->| enforceCallingPermission      |
  |                         |                              | read target binder/code/flags |
  |                         |                              | rest of parcel = original bytes
  |                         |                              | clearCallingIdentity()        |
  |                         |                              | target.transact(code, parcel, reply, flags)
  |                         |                              | ----------------------------->| executes AS SERVER UID (2000/0)
  |<------------------------|<-----------------------------|<-----------------------------| reply parcel returned verbatim
```

### D10 — Disconnect and recovery

```
Event                         Server side                                   Client side
---------------------------   -------------------------------------------   ------------------------------------------
Client process dies           DeathRecipient in addClient removes         (process gone)
                              ClientRecord; newProcess children of that
                              client destroyed (token death); non-daemon
                              user services removed
Permission revoked (manager)  records.allowed=false; forceStopPackage;    Process killed by force-stop; on next start
                              user services for package removed;          it re-attaches with allowed=false from config
                              config + runtime permission updated
Server exits / killed         Process gone; user-service processes exit   Binder DeathRecipient: state cleared,
                              on server-binder death; clients' binders    binderDead listeners fire; apps wait —
                              become dead objects                         no client-side restart exists
Server started again          Initial sweep + observers push binder       onBinderReceived -> attachApplication (D6)
                              to running/eligible apps (D5)               -> received listeners fire; app re-acquires
                                                                            user services / processes as needed
Device reboot                 Nothing runs                                  Manager BootCompleteReceiver may restart
                                                                            root mode, or ADB mode only under the
                                                                            API>=33 + WRITE_SECURE_SETTINGS
                                                                            conditions of §10; otherwise manual
```

## 13. Implications for Azm Admin

What Azm actually consumes (from `REVERSE_ENGINEERING.md` Stages 4–5): the
`pingBinder`/`checkSelfPermission`/`requestPermission(911)`/`getVersion`/`getUid`
state-and-auth surface, `IShizukuService.newProcess` shell execution for 13
provisioning commands, and `ShizukuBinderWrapper`-wrapped `DevicePolicyManager` calls
(`setActiveAdmin`, `setDeviceOwner`, `forceUpdateUserSetupComplete`). Everything else
in this document — user services, rish, system properties, SELinux context, hidden
flags — is unused by Azm and should **not** be cloned.

Design consequences for the custom typed-API replacement:

1. **Keep the two hard parts, drop the generic parts.** The genuinely load-bearing
   mechanisms are (a) a starter that only runs as UID 0/2000 and daemonizes an
   `app_process` server (§2), and (b) push-based binder delivery through the client's
   ContentProvider plus process/UID observers (§6). Azm Admin needs both, with its
   own identity (its own manager package, provider authority suffix, and permission
   name) instead of Shizuku's `moe.shizuku.*` names. The generic `transactRemote`
   proxy (§9/D9) and generic `newProcess` shell should be replaced by typed AIDL
   methods that perform the DPM/settings/pm operations **inside** the server
   process, where the server can validate arguments — the upstream design's own
   gate (§8.1) proves authorization must live server-side, and a typed surface
   shrinks it to an allow-list.
2. **Authorization can be far simpler.** Upstream needs per-UID persistent grants
   for arbitrary third-party apps, hence the JSON config, runtime-permission
   mirroring, reconciliation, and manager confirmation activity (§8). Azm Admin
   serves exactly one client (Azm, fixed package + signature): the server can pin
   that package/signature at attach time (the `getPackagesForUid` ownership proof
   of §7 step 2 is the part worth keeping verbatim in spirit) and keep a single
   persisted allow decision. The confirmation-UI round trip (D7) is still the right
   pattern for first-run consent, but it can be Azm Admin's own activity without
   the work-profile and legacy branches.
3. **Lifecycle honesty transfers directly.** Upstream never claims success from the
   starter's exit: the UI waits for the binder push (§5.4), `exit()` is
   manager-gated, restart is kill-by-name + start, and there is no boot recovery
   except the narrow root / API ≥ 33 wireless cases (§10). Azm Admin should keep
   exactly that posture: state APIs (`ping/getVersion/getUid` equivalents) report
   only a live binder's answers, and any "server running" UI state must be backed
   by a completed attach handshake like D6.
4. **Do not inherit the sharp edges.** The 10 s batched config write (§8.3) can lose
   a just-granted decision if the server is killed; a typed design with one client
   can write synchronously. The reply-version downgrade hack (§7 step 4) exists only
   because upstream must interop with older client libraries; a single-version
   Azm ↔ Azm Admin protocol should negotiate one version constant and fail closed
   on mismatch. The dead-provider force-stop retry (§6.3 step 3) is a workaround
   worth keeping in mind if Azm Admin's provider push ever reports a dead binder.
5. **User services and rish are the correct cuts.** They are the largest attack
   surface in upstream (arbitrary client code running as shell/root, §9) and Azm
   needs neither: its privileged work is a fixed, enumerable operation set, which
   is precisely what a typed server-side API expresses.

---
*Verification scope: all sections were read line-by-line from the clone files cited.
Two areas are summarized from their call sites rather than read in full: the native
pairing implementation (`jni/adb_pairing.cpp`, `AdbPairingClient.kt` internals) in
§5.3/D2, and the rish native/terminal internals in §11. Generated AIDL Stub classes
are build artifacts and are not present in the clone; transaction-code derivation is
covered in `BINDER_PROTOCOL.md` §1.*
