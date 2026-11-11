package io.github.alnajashia.azmadmin.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLogicTest {

    @Test
    fun flattenComponentShortensInPackageClass() {
        assertEquals(
            "io.github.alnajashia.dominanter/.deviceowner.AdminReceiver",
            ServerLogic.flattenComponent(
                "io.github.alnajashia.dominanter",
                "io.github.alnajashia.dominanter.deviceowner.AdminReceiver",
            ),
        )
    }

    @Test
    fun flattenComponentKeepsForeignClassFullyQualified() {
        assertEquals(
            "com.example.app/org.other.Receiver",
            ServerLogic.flattenComponent("com.example.app", "org.other.Receiver"),
        )
    }

    @Test
    fun accessibilityAddAppendsAndPreservesOthers() {
        val component = "io.github.alnajashia.dominanter/.accessibility.DominanterAccessibilityService"
        val updated = ServerLogic.updatedAccessibilityList(
            "com.other/.OtherService",
            component,
            enabled = true,
        )
        assertEquals("com.other/.OtherService:$component", updated)
    }

    @Test
    fun accessibilityRemoveLastEntryReturnsNull() {
        val component = "io.github.alnajashia.dominanter/.accessibility.DominanterAccessibilityService"
        val updated = ServerLogic.updatedAccessibilityList(component, component, enabled = false)
        assertNull(updated)
    }

    @Test
    fun accessibilityAddWhenNullListStartsFresh() {
        val component = "a.b/.Svc"
        assertEquals(component, ServerLogic.updatedAccessibilityList("null", component, enabled = true))
    }

    @Test
    fun parseCurrentOwnerReadsAdminFragment() {
        val output = "Device Owner: admin=io.github.alnajashia.dominanter/.deviceowner.AdminReceiver, package=io.github.alnajashia.dominanter"
        val owner = ServerLogic.parseCurrentOwner(output)
        assertEquals("io.github.alnajashia.dominanter", owner?.first)
        assertEquals(
            "io.github.alnajashia.dominanter/.deviceowner.AdminReceiver",
            owner?.second,
        )
    }

    @Test
    fun parseCurrentOwnerEmptyWhenNoOwner() {
        assertNull(ServerLogic.parseCurrentOwner("No device owner"))
    }

    @Test
    fun parseAccountsExtractsNameAndType() {
        val output = "    Account {name=ahmed@example.com, type=com.google}\n    Account {name=x, type=y}"
        val accounts = ServerLogic.parseAccounts(output)
        assertEquals(2, accounts.size)
        assertEquals("ahmed@example.com" to "com.google", accounts[0])
    }

    @Test
    fun parseExtraUsersSkipsUserZero() {
        val output = "Users:\n\tUserInfo{0:Owner:4d0} running\n\tUserInfo{10:Guest:410}"
        val users = ServerLogic.parseExtraUsers(output)
        assertEquals(listOf(10 to "Guest"), users)
    }

    @Test
    fun sha256HexIsLowercase64() {
        val hex = ServerLogic.sha256Hex("abc".toByteArray())
        assertEquals(64, hex.length)
        assertTrue(hex.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
    }
}
