# BINDER PROTOCOL — Shizuku IPC, method by method

Source of truth: the local clone at `~/workspace/dominater-rebuild/sources/shizuku`
(HEAD `b844bc4`), API submodule in `api/`. No web sources were used. Every claim is
cited as `path — Class.method/field`. Architecture narrative: `SHIZUKU_ARCHITECTURE.md`
in this folder. Azm's actual usage: `REVERSE_ENGINEERING.md` in this folder.

---

## 1. Conventions that apply to every interface

### 1.1 Interface descriptor

All Shizuku interfaces are reached through one binder whose AIDL descriptor is
`moe.shizuku.server.IShizukuService`
(`api/shared/.../ShizukuApiConstants.java — BINDER_DESCRIPTOR`). Every transaction's
data parcel begins with `writeInterfaceToken(BINDER_DESCRIPTOR)`, and the server
enforces it (`data.enforceInterface(...)` in the hand-dispatched branches of
`api/server-shared/.../Service.java — Service.onTransact` and
`server/.../ShizukuService.java — ShizukuService.onTransact`; generated Stub code does
the same for AIDL-dispatched methods).

### 1.2 Transaction codes: AIDL id vs wire code

`IShizukuService.aidl` and `IShizukuApplication.aidl` assign explicit ids
(`int getVersion() = 2;` etc.). The AIDL compiler emits
`TRANSACTION_<name> = IBinder.FIRST_CALL_TRANSACTION + <id>`, and
`FIRST_CALL_TRANSACTION = 1`, so **the code on the wire is `id + 1`**. Two in-repo
proofs:

- `api/api/.../Shizuku.java — attachApplicationV13` calls
  `binder.transact(18 /* IShizukuService.Stub.TRANSACTION_attachApplication */, …)`
  for the method declared `attachApplication(…) = 17` in
  `api/aidl/.../IShizukuService.aidl`.
- `api/api/.../Shizuku.java — bindUserService` javadoc: the user-service destroy
  transaction "is `16777115` (use `16777114` in aidl)" — again wire = AIDL value + 1;
  the constant itself is `api/shared/.../ShizukuApiConstants.java —
  USER_SERVICE_TRANSACTION_destroy = 16777115`.

Both tables below therefore list **AIDL id** and **wire code**. Interfaces without
explicit ids (`IRemoteProcess`, `IShizukuServiceConnection`) are numbered sequentially
in declaration order starting at `FIRST_CALL_TRANSACTION` (wire 1) — standard AIDL
behaviour; the generated Stub sources are build artifacts and are **not** in the clone,
so those two tables rest on the AIDL declaration order plus the client/server code that
consumes them (flagged again in §10).

Three codes on the `IShizukuService` binder are **hand-dispatched before** the AIDL
Stub sees them and are used literally, with no +1:

| Wire code | Meaning | Dispatch |
|---|---|---|
| 1 | `transactRemote` (binder-wrapper relay, §4) | `ShizukuApiConstants.BINDER_TRANSACTION_transact`; `Service.onTransact` first branch |
| 14 | Legacy (pre-v13) `attachApplication`, old parcel format (§5.3) | `Service.onTransact` second branch, commented `attachApplication <= v12` |
| 10001 | `getApplications(userId)` for the manager's app list | `server/.../ServerConstants.java — BINDER_TRANSACTION_getApplications`; `ShizukuService.onTransact` |

And one offset range is carved out on the same binder: rish shell sub-transactions
start at 30000 (`Service.<init>` → `RishConfig.init(BINDER_DESCRIPTOR, 30000)`;
`api/rish/.../RishConfig.java — TRANSACTION_createHost/setWindowSize/getExitCode =
0/1/2`, dispatched last in `Service.onTransact` via `rishService.onTransact`).

The AIDL id space of `IShizukuService` has deliberate gaps: ids 1, 5, 6 and 13 are
unassigned in the current AIDL (id 1 would collide conceptually with the raw
`transactRemote` code; the others are historical removals — the file itself gives no
reason, so none is claimed here).

### 1.3 Parcel and exception conventions

For AIDL-declared methods the generated Stub/Proxy pair defines the parcel layout:
data = interface token, then arguments in declaration order (`in` parameters only for
these interfaces; `Bundle`/`String[]` are written as standard Android Parcelables /
string arrays; binders as strong binders). Reply = a leading exception header
(`writeNoException()` / `readException()` — visible explicitly in the hand-written
callers, e.g. `manager/.../AuthorizationManager.kt — getApplications` and
`Shizuku.attachApplicationV13`), then the return value in declaration order. Server
implementations signal denial by throwing `SecurityException` (permission gates,
`api/server-shared/.../Service.java — enforceCallingPermission/enforceManagerPermission`)
which the Stub serializes into the exception header; several implementations wrap
failures as `IllegalStateException` (e.g. `Service.newProcess` wraps `IOException`,
`Service.getSystemProperty` wraps any `Throwable`).

`oneway` methods (`IShizukuApplication` both methods, `IShizukuServiceConnection` both
methods, and `IShizukuService.dispatchPackageChanged` /
`dispatchPermissionConfirmationResult`) are sent with `FLAG_ONEWAY` semantics: no reply
parcel is waited on and server exceptions cannot reach the caller.

### 1.4 Version constants

| Constant | Value | Source |
|---|---|---|
| `SERVER_VERSION` | 13 | `api/shared/.../ShizukuApiConstants.java` |
| `SERVER_PATCH_VERSION` | 6 | same file |
| Client library version Azm pins | 13.1.5 (`dev.rikka.shizuku:api` + `:provider`) | `REVERSE_ENGINEERING.md` Stage 4 (Azm project files) |
| `ShizukuConfig.LATEST_VERSION` (persisted config schema) | 2 | `server/.../ShizukuConfig.java` |
| User-service destroy transaction (wire) | 16777115 | `ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy` |
| Manager-not-found server exit code | 50 | `server/.../ServerConstants.java — MANAGER_APP_NOT_FOUND` |
| API permission string | `moe.shizuku.manager.permission.API_V23` | `ServerConstants.PERMISSION` (duplicated as `BinderSender.PERMISSION` and `ShizukuProvider.PERMISSION`) |
| Manager package | `moe.shizuku.privileged.api` | `ServerConstants.MANAGER_APPLICATION_ID` |

Server-side version handling is concentrated in `attachApplication`
(`server/.../ShizukuService.java`): the client reports its API version in the attach
bundle (§5); a client reporting **no** version (`-1`, the 12.2.0 library) is answered
with server version **12** instead of 13, because its binder wrapper already uses the
v13 transact format (in-code comment). The recorded per-client `apiVersion`
(`ClientRecord.apiVersion`) then drives two behaviours: the `transactRemote` flags
location (§4.3) and the `addUserService` return-value convention (§3, addUserService).
Client side, `Shizuku.getVersion()`/`getUid()`/`getSELinuxContext()` return the values
cached from the attach reply and only fall back to live AIDL calls when uncached; a
`SecurityException` on those fallbacks (pre-v11 server, permission not granted) is
translated to `-1`/`null` (`api/api/.../Shizuku.java — getUid/getVersion/
getSELinuxContext`). `Shizuku.isPreV11()` is set only when both attach attempts fail
(§5.3).

## 2. `IShizukuService` — the main interface

AIDL: `api/aidl/src/main/aidl/moe/shizuku/server/IShizukuService.aidl`.
Implementation: abstract base `api/server-shared/.../Service.java — Service` (most
methods `final`), concrete overrides in `server/.../ShizukuService.java —
ShizukuService`. "Gate" column: `C` = `enforceCallingPermission` (§8.1 of the
architecture doc), `M` = manager-only, `R` = `requireClient`-based, `—` = the method
performs its own inline check or none.

| Method (AIDL signature) | AIDL id | Wire | Gate | Server behaviour |
|---|---|---|---|---|
| `int getVersion()` | 2 | 3 | C | Returns `ShizukuApiConstants.SERVER_VERSION` (13). `Service.getVersion` |
| `int getUid()` | 3 | 4 | C | Returns `Os.getuid()` of the server process (2000 adb / 0 root). `Service.getUid` |
| `int checkPermission(String permission)` | 4 | 5 | C | `PermissionManagerApis.checkPermission(permission, Os.getuid())` — checks the **server's** grants, not the caller's. `Service.checkPermission` |
| `IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir)` | 7 | 8 | C | `Runtime.getRuntime().exec(cmd, env, dir!=null ? new File(dir) : null)` in the server process; returns `RemoteProcessHolder(process, clientBinderToken)` (§7). `IOException` → `IllegalStateException`. `Service.newProcess` |
| `String getSELinuxContext()` | 8 | 9 | C | `SELinux.getContext()` of the server process; failure → `IllegalStateException`. `Service.getSELinuxContext` |
| `String getSystemProperty(in String name, in String defaultValue)` | 9 | 10 | C | `SystemProperties.get(name, defaultValue)`. `Service.getSystemProperty` |
| `void setSystemProperty(in String name, in String value)` | 10 | 11 | C | `SystemProperties.set(name, value)` — as shell/root this can set properties the caller never could. `Service.setSystemProperty` |
| `int addUserService(in IShizukuServiceConnection conn, in Bundle args)` | 11 | 12 | C | Delegates to `UserServiceManager.addUserService(conn, options, callingApiVersion)`; returns 0 on start/reuse, or for `noCreate` peeks the record's version code (API ≥ 13) / 0 (pre-13 running), `-1` (API ≥ 13) / `1` (pre-13) when not running. `Service.addUserService`, `UserServiceManager.addUserService` |
| `int removeUserService(in IShizukuServiceConnection conn, in Bundle args)` | 12 | 13 | C | `UserServiceManager.removeUserService`: unregisters the connection, and destroys the record unless the args carry `shizuku:user-service-remove=false` (newer clients); returns 1 if no record existed, else 0 |
| `void requestPermission(int requestCode)` | 14 | 15 | R | Full flow in `SHIZUKU_ARCHITECTURE.md` §8.2 / diagram D7. Self-call no-ops; `requireClient` throws for unattached callers; allowed → immediate result `true`; config-denied → immediate result `false`; else manager UI. `Service.requestPermission` |
| `boolean checkSelfPermission()` | 15 | 16 | R | `true` for server-self calls; else `requireClient(uid,pid).allowed`. `Service.checkSelfPermission` |
| `boolean shouldShowRequestPermissionRationale()` | 16 | 17 | R | `true` for server-self calls (sic — returns true); else whether the UID's config entry `isDenied()`. `Service.shouldShowRequestPermissionRationale` |
| `void attachApplication(in IShizukuApplication application, in Bundle args)` | 17 | 18 | — (identity proof inside) | §5. `ShizukuService.attachApplication` |
| `void exit()` | 100 | 101 | M | `enforceManagerPermission("exit")`, then `System.exit(0)`. `ShizukuService.exit` |
| `void attachUserService(in IBinder binder, in Bundle options)` | 101 | 102 | M | `enforceManagerPermission` in `ShizukuService.attachUserService`, then `UserServiceManager.attachUserService` matches `options[shizuku:user-service-arg-token]` to a record and sets its binder |
| `oneway void dispatchPackageChanged(in Intent intent)` | 102 | 103 | — | **No-op in Shizuku** (`ShizukuService.dispatchPackageChanged` has an empty body, commented "Sui only"); exists for the Sui variant sharing this AIDL |
| `boolean isHidden(int uid)` | 103 | 104 | — | Always `false` in Shizuku (`ShizukuService.isHidden`, "Sui only") |
| `oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data)` | 104 | 105 | M (inline appId check; non-manager callers are logged and ignored, not thrown at — and being `oneway` the caller could not see a throw anyway) | Return half of the permission flow: applies `allowed` to all records of the UID, dispatches the result to the matching pid, persists unless `onetime`, mirrors grant into runtime permissions when allowed. `ShizukuService.dispatchPermissionConfirmationResult` |
| `int getFlagsForUid(int uid, int mask)` | 105 | 106 | M (inline; returns 0 for non-managers) | Config flags for the UID masked by `mask`; falls back to the runtime-permission state when no config entry exists. `ShizukuService.getFlagsForUid/getFlagsForUidInternal` |
| `void updateFlagsForUid(int uid, int mask, int value)` | 106 | 107 | M (inline; silently returns for non-managers) | Manager app-list grant/revoke: updates live records, force-stops + strips user services on revoke, mirrors to runtime permissions, persists. `ShizukuService.updateFlagsForUid` |

Flag constants used by the last two methods and by the manager:
`ConfigManager.FLAG_ALLOWED = 1<<1`, `FLAG_DENIED = 1<<2`,
`MASK_PERMISSION = FLAG_ALLOWED|FLAG_DENIED`
(`api/server-shared/.../ConfigManager.java`; mirrored privately in
`manager/.../AuthorizationManager.kt`).

### 2.1 Raw transaction 10001 — `getApplications`

Hand-written both sides. Request parcel: interface token, then one `int userId`
(`-1` = all users). Reply: exception header, then a
`rikka.parcelablelist.ParcelableListSlice<PackageInfo>` written with
`PARCELABLE_WRITE_RETURN_VALUE`. Server: `ShizukuService.onTransact` →
`getApplications(userId)` returns only the manager package's complement: apps with a
non-zero persisted permission flag, or apps declaring meta-data
`moe.shizuku.client.V3_SUPPORT=true` **and** requesting the API permission; the manager
package itself is excluded. Client: `AuthorizationManager.getApplications`. There is
**no server-side caller check in the `onTransact` branch itself** beyond the interface
token — in practice the binder is only held by attached clients and the manager, but
as written any holder of the binder can enumerate this list; recorded here as a
source fact, not a recommendation.

## 3. `IShizukuApplication` — server → client callbacks

AIDL: `api/aidl/.../IShizukuApplication.aidl`. Client implementation:
`api/api/.../Shizuku.java — Shizuku.SHIZUKU_APPLICATION` (a static Stub in every client
process). Server holder: `ClientRecord.client`.

| Method | AIDL id | Wire | Direction / semantics |
|---|---|---|---|
| `oneway void bindApplication(in Bundle data)` | 1 | 2 | The attach reply (§5.2). Client copies the bundle into static state and fires binder-received listeners |
| `oneway void dispatchRequestPermissionResult(int requestCode, in Bundle data)` | 2 | 3 | Permission answer; `data` contains only `shizuku:request-permission-reply-allowed` (boolean). Client maps it to `PERMISSION_GRANTED`/`PERMISSION_DENIED` for its listeners. Server sender: `ClientRecord.dispatchRequestPermissionResult` |
| `void showPermissionConfirmation(int requestUid, int requestPid, in String requestPackageName, int requestCode)` | 10000 | 10001 | **Sui only** (marked in the AIDL). The Shizuku client's implementation is an empty "non-app" stub (`Shizuku.SHIZUKU_APPLICATION.showPermissionConfirmation`); Shizuku's server never calls it — it starts the manager activity instead |

## 4. `transactRemote` and `ShizukuBinderWrapper` — the generic privileged relay

This is the mechanism Azm uses for its DevicePolicyManager calls, so it is specified
exactly.

### 4.1 Client entry points

- `api/api/.../Shizuku.java — Shizuku.transactRemote(Parcel data, Parcel reply, int
  flags)`: `requireService().asBinder().transact(BINDER_TRANSACTION_transact /* =1 */,
  data, reply, flags)`. Note what this means: the **outer** transaction code on the
  server binder is literally `1`, and `data` is the parcel built by the wrapper below.
- `api/api/.../ShizukuBinderWrapper.java — ShizukuBinderWrapper` is an `IBinder`
  decorator around a real system-service binder (obtained in the client process via
  `SystemServiceHelper.getSystemService(name)` → reflective
  `ServiceManager.getService`, `api/api/.../SystemServiceHelper.java`). A generated
  AIDL proxy for the target interface (e.g. `IDevicePolicyManager.Stub.asInterface(
  new ShizukuBinderWrapper(dpmBinder))`) believes it is talking to the system service:
  the proxy marshals its normal parcel — target interface token followed by the call's
  arguments — and calls `wrapper.transact(code, data, reply, flags)`.

### 4.2 What the wrapper puts on the wire

`ShizukuBinderWrapper.transact(code, data, reply, flags)`, step by step:

1. Computes `atLeast13 = !Shizuku.isPreV11() && Shizuku.getVersion() >= 13` (the
   server version from the attach reply, §5).
2. Obtains a fresh parcel `newData` and writes, in order:
   `writeInterfaceToken("moe.shizuku.server.IShizukuService")`;
   `writeStrongBinder(original)` — the **real target binder**;
   `writeInt(code)` — the target transaction code;
   if `atLeast13`: `writeInt(flags)`;
   then `appendFrom(data, 0, data.dataSize())` — the caller's **entire original
   parcel verbatim**, including the target interface token the proxy already wrote
   into it.
3. Calls `Shizuku.transactRemote(newData, reply, 0)` when `atLeast13`, else
   `transactRemote(newData, reply, flags)` — pre-13 the flags travel as the outer
   Binder transaction flags instead of inside the parcel.
4. Always returns `true` (the reply parcel is filled by the remote call); recycles
   `newData` in `finally`.

All other `IBinder` methods delegate to the original binder (`pingBinder`,
`linkToDeath`, `dump`, `getInterfaceDescriptor`), except `queryLocalInterface` which
returns `null` — deliberately, so a proxy can never shortcut to a local object and
skip the relay.

### 4.3 Server side

`api/server-shared/.../Service.java — Service.onTransact` (code 1 branch) →
`Service.transactRemote(data, reply, flags)`:

1. `enforceCallingPermission("transactRemote")` — the full §8.1 gate; an unauthorized
   client gets `SecurityException` and nothing is relayed.
2. Reads from `data` (after the interface token the Stub layer enforced):
   `targetBinder = data.readStrongBinder()`; `targetCode = data.readInt()`;
   `targetFlags = data.readInt()` **iff** the caller's `ClientRecord.apiVersion >=
   13`, otherwise `targetFlags = flags` (the outer transaction's flags). This is the
   negotiation the attach-time version hack (§1.4) exists to keep consistent.
3. Copies the **remainder** of the parcel — `data` from its current position to the
   end, i.e. exactly the original target parcel the wrapper appended — into a fresh
   parcel `newData` (`appendFrom(data, data.dataPosition(), data.dataAvail())`). On
   copy failure it logs and returns silently (no exception, no reply content).
4. Executes the relay under the server's identity:
   `long id = Binder.clearCallingIdentity(); targetBinder.transact(targetCode,
   newData, reply, targetFlags); Binder.restoreCallingIdentity(id);`
   `clearCallingIdentity` is the privilege mechanism in one call: the target system
   service sees the **server process** as the Binder caller, i.e. UID 2000 (adb mode)
   or UID 0 (root mode), with the server's permissions and app-ops identity — not the
   client app's.
5. The target writes its reply (exception header + results) directly into the
   `reply` parcel supplied by the client, so return values, exceptions and parcelled
   results flow back through the wrapper to the proxy unchanged. The server performs
   **no validation** of target, code, or arguments beyond the permission gate in
   step 1: any attached, allowed client can relay **any** transaction to **any**
   binder it can name. That generality is the design's power and its entire risk
   model, and it is the main thing Azm Admin's typed API removes (§9).

## 5. `attachApplication` in detail

### 5.1 Request (v13 format, hand-marshalled)

`Shizuku.attachApplicationV13` does not use the generated Proxy; it writes manually:
interface token; `writeStrongBinder(SHIZUKU_APPLICATION.asBinder())`;
`writeInt(1)`; then `args.writeToParcel(data, 0)`. The `writeInt(1)` is the
Parcelable "present" marker for the `Bundle` argument in the AIDL-generated Stub's
reading order (binder, then bundle-presence int, then bundle). `args` keys
(`api/shared/.../ShizukuApiConstants.java`):

- `shizuku:attach-package-name` (String) — `ATTACH_APPLICATION_PACKAGE_NAME`
- `shizuku:attach-api-version` (int) — `ATTACH_APPLICATION_API_VERSION`, value
  `SERVER_VERSION` (13) from the client library

Wire code: 18 (§1.2). Reply handling: `reply.readException()` only — all real output
arrives asynchronously through the `bindApplication` callback (§5.2), which is why the
AIDL method itself returns `void`.

### 5.2 Reply (via `IShizukuApplication.bindApplication`)

Server-built `Bundle` (`ShizukuService.attachApplication`), keys from
`ShizukuApiConstants`:

- `shizuku:attach-reply-uid` (int) — server UID (`OsUtils.getUid()`)
- `shizuku:attach-reply-version` (int) — 13, or **12** for version-less legacy
  clients (§1.4)
- `shizuku:attach-reply-patch-version` (int) — 6
- `shizuku:attach-reply-secontext` (String) — server SELinux context
- Non-manager clients only: `shizuku:attach-reply-permission-granted` (boolean) =
  the new record's `allowed`; `shizuku:attach-reply-should-show-request-permission-
  rationale` (boolean) = `false` (hard-coded; rationale is computed live by the
  dedicated AIDL call)
- Manager only: neither permission key; instead the server grants the manager
  `WRITE_SECURE_SETTINGS` as a side effect

### 5.3 Legacy format (pre-v13)

`Shizuku.attachApplicationV11`: interface token; strong binder; `writeString(
packageName)`; wire code **14**. `Service.onTransact` intercepts raw code 14,
rebuilds an equivalent modern call (`args` with the package name and API version
`-1`) and invokes `attachApplication` directly, writing `writeNoException()` to the
reply. Clients whose library predates the Bundle format — and clients talking to a
pre-v11 server, where even this fails — end in the `preV11` state (§1.4). The current
server supports both formats; nothing in the current client emits the v11 format
except as this fallback.

### 5.4 Server validation summary

Null callback/args or missing package name → silent return (no record, no reply).
Claimed package not in `getPackagesForUid(callingUid)` → `SecurityException`.
Duplicate attach from the same `(uid, pid)` → the existing record is kept (no second
record), but a fresh `bindApplication` reply is still sent. Full narrative:
`SHIZUKU_ARCHITECTURE.md` §7.

## 6. `IShizukuServiceConnection` — user-service callbacks

AIDL: `api/aidl/.../IShizukuServiceConnection.aidl` — two `oneway` methods, no explicit
ids, so wire codes are sequential from 1 in declaration order (§1.2 caveat applies):

| Method | Wire | Semantics |
|---|---|---|
| `oneway void connected(IBinder service)` | 1 | Server → client: the user service's binder is ready. Server sender: `UserServiceRecord.broadcastBinderReceived` iterates the record's `RemoteCallbackList<IShizukuServiceConnection>` |
| `oneway void died()` | 2 | Server → client: the user service binder died. Server sender: `UserServiceRecord.broadcastBinderDied` |

Client implementation: `api/api/.../ShizukuServiceConnection.java —
ShizukuServiceConnection extends IShizukuServiceConnection.Stub`, one instance per
`UserServiceArgs` (cached in `ShizukuServiceConnections`). `connected(binder)` holds a
strong reference to the binder (required for its death link to stay meaningful),
`linkToDeath(this::died)`, and posts `ServiceConnection.onServiceConnected(
componentName, binder)` to the main thread for every registered Android
`ServiceConnection`. `died()` posts `onServiceDisconnected`, clears the connection
set, and unregisters itself from `ShizukuServiceConnections`; a `dead` flag makes it
idempotent.

Related user-service constants (`ShizukuApiConstants`): the args-bundle keys
`shizuku:user-service-arg-{tag,component,debuggable,version-code,process-name,
no-create,daemon,use-32-bit-app-process}`, `shizuku:user-service-remove`, and the
hand-back token key `shizuku:user-service-arg-token` whose value is redacted from this
document because the source extraction pipeline flagged the literal; it is defined in
`api/shared/.../ShizukuApiConstants.java — USER_SERVICE_ARG_TOKEN` and used by
`ServiceStarter`, `ShizukuManagerProvider` and `UserServiceManager.attachUserService`
exactly as cited in `SHIZUKU_ARCHITECTURE.md` §9.

## 7. `IRemoteProcess` — the `newProcess` handle

AIDL: `api/aidl/.../IRemoteProcess.aidl` — no explicit ids; wire codes are sequential
from 1 in declaration order (§1.2 caveat). Server implementation:
`api/server-shared/.../RemoteProcessHolder.java — RemoteProcessHolder extends
IRemoteProcess.Stub`. Client wrapper: `api/api/.../ShizukuRemoteProcess.java —
ShizukuRemoteProcess extends java.lang.Process implements Parcelable` (parcelled by
writing the `IRemoteProcess` binder itself).

| Method | Wire | Parcel / semantics |
|---|---|---|
| `ParcelFileDescriptor getOutputStream()` | 1 | Child's **stdin**. Server lazily creates `ParcelFileDescriptorUtil.pipeTo(process.getOutputStream())` once and returns the same PFD |
| `ParcelFileDescriptor getInputStream()` | 2 | Child's **stdout**, via `pipeFrom(process.getInputStream())`, lazily created once. (Naming follows `java.lang.Process` from the caller's perspective, hence the apparent inversion) |
| `ParcelFileDescriptor getErrorStream()` | 3 | Child's **stderr**, via `pipeFrom(...)`, created fresh on each call server-side; client wraps each call in a new `AutoCloseInputStream` |
| `int waitFor()` | 4 | Blocks server-side in `process.waitFor()`; `InterruptedException` → `IllegalStateException` across the wire |
| `int exitValue()` | 5 | `process.exitValue()`; throws `IllegalThreadStateException` (a local exception type — across Binder it surfaces via the exception header) while the child runs |
| `void destroy()` | 6 | `process.destroy()` (SIGTERM semantics of `java.lang.Process`) |
| `boolean alive()` | 7 | Implemented as `exitValue()` try/catch: `IllegalThreadStateException` → `true` |
| `boolean waitForTimeout(long timeout, String unit)` | 8 | `unit` is a `java.util.concurrent.TimeUnit` **name string** (`unit.toString()` client-side, `TimeUnit.valueOf(unitName)` server-side); server polls `exitValue()` with ≤ 100 ms sleeps until the deadline |

Ownership/death: `RemoteProcessHolder.<init>` links a death recipient to the
**client's** `IShizukuApplication` binder passed in by `Service.newProcess` (the
client record's binder, or `null` for unattached-but-permitted callers). If the client
dies while the child runs, the holder destroys the child and logs "destroy process
because the owner is dead". Client side, `ShizukuRemoteProcess.<init>` links to the
`IRemoteProcess` binder's death: on death it nulls its remote reference, logs, and
removes itself from the static `CACHE` set that exists solely to keep wrappers (and
thus the server-side holder reference) alive.

Azm's use (`REVERSE_ENGINEERING.md` Stage 5): `ShizukuRunner` calls
`IShizukuService.newProcess` for its 13 provisioning shell commands and — unlike stock
Shizuku consumers — checks the exit code via this interface, which is the behaviour
Azm Admin's typed API must preserve operation-by-operation.

## 8. Death handling — consolidated

| Link | Registered where | Effect on death |
|---|---|---|
| Server → client app binder | `ClientManager.addClient`: `client.asBinder().linkToDeath(() -> clientRecords.remove(record))` | The `(uid,pid)` record vanishes; subsequent calls from a restarted client process must re-attach. `linkToDeath` failure at add time aborts registration |
| Client → server binder | `Shizuku.onBinderReceived`: `binder.linkToDeath(DEATH_RECIPIENT)`; unlinked when replaced | Client wipes all cached server state, fires dead listeners; recovery only via a new push + attach (§10/D10 of the architecture doc) |
| Server → client token for `newProcess` | `RemoteProcessHolder.<init>` | Child process destroyed (§7) |
| Client → `IRemoteProcess` binder | `ShizukuRemoteProcess.<init>` | Wrapper self-invalidates and leaves the keep-alive cache |
| Server → user-service binder | `UserServiceRecord.setBinder`: `binder.linkToDeath(deathRecipient)` | Record removes itself (`removeSelf`) |
| Server → connection callbacks | `UserServiceRecord.ConnectionList extends RemoteCallbackList` | `onCallbackDied`: non-daemon records with zero remaining callbacks remove themselves; daemon records persist |
| User-service process → server binder | `ServiceStarter.sendBinder`: `shizukuBinder.linkToDeath(() -> System.exit(0))` | User services never outlive the server |
| Client → user-service binder | `ShizukuServiceConnection.connected`: `binder.linkToDeath(this::died)` | Client translates binder death into `died()` → `onServiceDisconnected` |

There is no heartbeat or ping protocol anywhere: `pingBinder()` checks (in
`Shizuku.pingBinder`, `ShizukuProvider.handleSendBinder`, user-service record reuse)
are purely local liveness probes of an already-held binder.

## 9. Implications for Azm Admin

Azm's consumed surface (from `REVERSE_ENGINEERING.md` Stages 4–5) maps onto this
protocol as: state/auth = `pingBinder` + `checkSelfPermission` (wire 16) +
`requestPermission` (wire 15) + the attach reply's cached `getVersion`/`getUid`;
execution = `newProcess` (wire 8) + `IRemoteProcess`; DPM = `transactRemote`
(wire 1) via `ShizukuBinderWrapper` against `IDevicePolicyManager`. A custom typed
protocol should therefore:

1. **Replace wire 1 (`transactRemote`) with named server-side operations.**
   §4.3 shows the relay validates nothing but the caller's grant: descriptor, target
   and transaction code are all client-chosen. Azm needs exactly three DPM effects
   (`setActiveAdmin`, `setDeviceOwner`, `forceUpdateUserSetupComplete`); making each
   (plus the settings/pm provisioning operations) a typed AIDL method lets the
   Azm Admin server validate package, arguments and current device state before
   acting as shell — strictly stronger than the upstream gate, with no loss for Azm.
2. **Replace wire 8 (`newProcess`) with the same typed operations, not a shell.**
   The 13 commands Azm runs are enumerable (see the dependency matrix in
   `REVERSE_ENGINEERING.md`); typed methods keep the one property Azm depends on —
   a checked result per operation (the `IRemoteProcess.waitFor` exit code today) —
   by returning explicit result codes, and remove arbitrary command execution as
   shell/root from the attack surface.
3. **Keep the attach handshake shape, shrink its contents.** The ownership proof in
   §5.4 (claimed package must belong to the calling UID) and the reply-bundle
   pattern (server UID, protocol version, grant state) are worth reusing almost
   verbatim for an `IAzmAdminService.attachApplication` equivalent — with one
   protocol version constant, fail-closed on mismatch, and no legacy-format branch
   (§5.3) or version-downgrade hack (§1.4), because Azm and Azm Admin ship together.
4. **Keep death semantics.** §8's links are the recovery contract: server must drop
   the client record on client death, kill in-flight work owned by a dead client,
   and clients must treat server-binder death as "all state invalid, re-attach on
   next push". A typed API that omits any of these will leak grants or child
   processes exactly where upstream does not.
5. **Do not allocate codes you do not need.** Upstream's gaps and Sui-only methods
   (§2) are the cost of a shared, evolving AIDL. Azm Admin's AIDL should be small,
   sequentially numbered from an explicit base, and versioned by the attach
   exchange — and `IRemoteProcess`, `IShizukuServiceConnection`, user-service arg
   keys (§6) and the rish range (§1.2) should exist only if a typed operation
   genuinely needs a streaming handle, which none of Azm's current operations do.

## 10. Verification notes — what could not be verified from the clone

- **Generated AIDL Stub/Proxy sources** are build outputs and are absent from the
  clone. Wire codes for explicitly-numbered interfaces are proven by §1.2's two
  in-repo data points; wire codes for `IRemoteProcess` and
  `IShizukuServiceConnection` follow standard AIDL sequential assignment from their
  declaration order and could not be cross-checked against a generated Stub in this
  tree.
- **`rikka.hidden.compat.*` and `rikka.parcelablelist`** implementations live outside
  this clone (external dependencies of the `server`/`server-shared` modules); their
  call sites and signatures were verified, their internals were not.
- **Rish native internals** (`api/rish` C++ and the terminal protocol beyond the
  three transaction offsets in §1.2) were not read; Azm does not use them.
- **ADB pairing cryptography** (`AdbPairingClient.kt` internals,
  `manager/src/main/jni/adb_pairing.cpp`) was verified at the service/flow level in
  `SHIZUKU_ARCHITECTURE.md` §5.3, not line-by-line.
- One literal constant value (`USER_SERVICE_ARG_TOKEN`, §6) is redacted in this
  document by the extraction pipeline; its definition site and all use sites are
  cited and were verified in source.
