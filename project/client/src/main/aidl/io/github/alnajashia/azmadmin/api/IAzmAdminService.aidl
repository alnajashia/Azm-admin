package io.github.alnajashia.azmadmin.api;

import io.github.alnajashia.azmadmin.api.AzmAdminResult;

// Azm Admin's own typed, allow-listed privileged interface (Strategy B).
// There is deliberately NO arbitrary shell execution and NO generic binder
// proxy in this interface: every method is one verified Azm operation.
// Authorization is enforced by the server on every privileged call from the
// Binder calling UID -> package -> signing-certificate record created when
// the user approved the client in the Azm Admin manager UI.
interface IAzmAdminService {

    /** Protocol/API version of the running server (currently 1). */
    int getApiVersion() = 1;

    /** UID the server process runs as (2000 = shell/ADB, 0 = root). */
    int getServerUid() = 2;

    /** JSON: {apiVersion, serverUid, mode, callerPackage, callerAuthorized}. */
    String getServerStateJson() = 3;

    /** 0 = not authorized, 1 = authorized, for the calling UID/package. */
    int checkAuthorization() = 4;

    /**
     * Registers a pending authorization request for the calling package and
     * asks the manager app to show its approval screen. The grant itself is
     * only ever recorded by the manager (see approveClient).
     */
    void requestAuthorization() = 5;

    /**
     * Makes [targetPackage] (which must be one of the caller's own packages)
     * Device Owner via its admin receiver [adminReceiverClassName]:
     * user_setup_complete bypass/restore, direct IDevicePolicyManager calls
     * in the server, dpm shell fallback, all verified by an owner re-read.
     */
    AzmAdminResult provisionDeviceOwner(String targetPackage, String adminReceiverClassName) = 6;

    /** pm grant [targetPackage] android.permission.WRITE_SECURE_SETTINGS, verified by re-check. */
    AzmAdminResult grantWriteSecureSettings(String targetPackage) = 7;

    /**
     * Adds/removes [targetPackage]'s accessibility service
     * ([serviceClassName] inside [targetPackage]) in
     * enabled_accessibility_services and sets accessibility_enabled 1,
     * verified by re-reading the setting.
     */
    AzmAdminResult setAccessibilityEnabled(String targetPackage, String serviceClassName, boolean enabled) = 8;

    /** JSON diagnostics: {accounts, extraUsers, currentOwner}. Authorized or manager callers only. */
    String getDiagnosticsJson() = 9;

    // ---- Manager-only (caller must be the Azm Admin manager app itself,
    // ---- verified server-side against the manager APK's own signature).

    /** JSON array of pending requests: [{packageName, uid, requestedAt}]. */
    String getPendingRequestsJson() = 10;

    /** JSON array of recorded grants: [{packageName, uid, certSha256, grantedAt}]. */
    String getAuthorizedClientsJson() = 11;

    /**
     * Records a grant for [packageName]: the server itself resolves the
     * package's current UID and signing-certificate SHA-256 and stores all
     * three. Values supplied by any caller are never trusted.
     */
    AzmAdminResult approveClient(String packageName) = 12;

    /** Removes the recorded grant for [packageName]. */
    AzmAdminResult revokeClient(String packageName) = 13;

    /** Stops the server process. Manager-only. */
    void exitServer() = 14;
}
