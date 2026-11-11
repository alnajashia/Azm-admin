package io.github.alnajashia.azmadmin.server

import java.security.MessageDigest

/**
 * Pure, Android-free helpers for the privileged server's operations, kept
 * separate so they are unit-testable on the JVM. The parsing rules mirror
 * exactly what Azm's Shizuku-based provisioning did (verified in the
 * Stage 4–5 audit), so the replacement reads the same system outputs the
 * same way.
 */
object ServerLogic {

    /** `pkg/.RelativeClass` when the class lives inside the package, else `pkg/fully.qualified.Class`. */
    fun flattenComponent(packageName: String, className: String): String =
        if (className.startsWith("$packageName.")) {
            "$packageName/${className.substring(packageName.length)}"
        } else {
            "$packageName/$className"
        }

    /**
     * Computes the new `enabled_accessibility_services` value: adds or
     * removes [component] (flattened `pkg/Class`), preserving every other
     * service. Returns null when the resulting list is empty (the caller
     * then writes the literal 'null', matching Azm's original semantics).
     */
    fun updatedAccessibilityList(currentRaw: String, component: String, enabled: Boolean): String? {
        val entries = currentRaw
            .split(":")
            .filter { it.isNotBlank() && it != "null" }
            .toMutableList()
        val listed = entries.any { it == component }
        if (enabled && !listed) entries.add(component)
        if (!enabled && listed) entries.removeAll { it == component }
        return if (entries.isEmpty()) null else entries.joinToString(":")
    }

    fun accessibilityListContains(currentRaw: String, component: String): Boolean =
        currentRaw.split(":").any { it == component }

    /**
     * Parses `dpm list-owners` output the way Azm did: the current owner is
     * read from the `admin=<component>` fragment. Returns
     * (packageName, flattenedReceiver) or null when no owner is listed.
     */
    fun parseCurrentOwner(listOwnersOutput: String): Pair<String, String>? {
        val componentPath = listOwnersOutput
            .substringAfter("admin=", "")
            .substringBefore(",", "")
            .trim()
        if (!componentPath.contains("/")) return null
        val parts = componentPath.split("/", limit = 2)
        val pkg = parts[0].trim()
        var receiver = parts[1].trim()
        if (receiver.startsWith(".")) receiver = "$pkg/$receiver"
        return pkg to receiver
    }

    /** Parses `dumpsys account` lines of the form `Account {name=X, type=Y}`. */
    fun parseAccounts(dumpsysAccountOutput: String): List<Pair<String, String>> {
        val regex = " {4}Account \\{name=(.*?), type=(.*?)\\}".toRegex()
        return dumpsysAccountOutput.lines().mapNotNull { line ->
            regex.find(line)?.let { match -> match.groupValues[1] to match.groupValues[2] }
        }
    }

    /** Parses `pm list users` entries other than user 0: (id, name). */
    fun parseExtraUsers(pmListUsersOutput: String): List<Pair<Int, String>> {
        val regex = Regex("""UserInfo\{(\d+):([^:]+):""")
        return regex.findAll(pmListUsersOutput).mapNotNull { match ->
            val id = match.groupValues[1].toInt()
            if (id == 0) null else id to match.groupValues[2]
        }.toList()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
