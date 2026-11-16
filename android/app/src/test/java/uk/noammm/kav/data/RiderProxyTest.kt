package uk.noammm.kav.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderProxyTest {
    @Test
    fun torIsOrbotsSocksPortOnThePhone() {
        val s = RiderProxy.tor()
        assertEquals(RiderProxy.Kind.TOR, s.kind)
        assertEquals("127.0.0.1", s.host)
        assertEquals(9050, s.port)
        assertEquals("", s.user)
        assertTrue(s.on)
        assertTrue(s.socks)
    }

    @Test
    fun torKeepsAMovedPort() {
        assertEquals(9150, RiderProxy.tor(9150).port)
        assertTrue(RiderProxy.tor(9150).on)
    }

    @Test
    fun torWithoutAPortIsOff() {
        assertFalse(RiderProxy.tor(0).on)
        assertFalse(RiderProxy.tor(70000).on)
    }

    @Test
    fun onlySocksAndTorGoOverSocks() {
        fun of(k: RiderProxy.Kind) = RiderProxy.Setting(k, "proxy.example", 1080, "", "")
        assertTrue(of(RiderProxy.Kind.SOCKS).socks)
        assertTrue(of(RiderProxy.Kind.TOR).socks)
        assertFalse(of(RiderProxy.Kind.HTTP).socks)
        assertFalse(of(RiderProxy.Kind.OFF).socks)
    }

    @Test
    fun readsTheTorProjectsAnswer() {
        assertTrue(RiderProxy.isTor("""{"IsTor":true,"IP":"185.220.101.1"}"""))
        assertFalse(RiderProxy.isTor("""{"IsTor":false,"IP":"203.0.113.7"}"""))
        assertFalse(RiderProxy.isTor("""{"IP":"203.0.113.7"}"""))
        assertFalse(RiderProxy.isTor("<html>blocked</html>"))
        assertFalse(RiderProxy.isTor(""))
    }
}
