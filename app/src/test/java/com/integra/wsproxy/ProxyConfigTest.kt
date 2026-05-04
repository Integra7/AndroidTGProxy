package com.integra.wsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigTest {

    @Test
    fun default_matchesExpectedListenAndDc() {
        val c = ProxyConfig.default()
        assertEquals("127.0.0.1", c.listenHost)
        assertEquals(1443, c.listenPort)
        assertEquals("3075abe65830f0325116bb0416cadf9f", c.secretHex)
        assertEquals(256 * 1024, c.bufferSize)
        assertEquals(25L, c.wsPingIntervalSeconds)
        assertEquals(512, c.wsIncomingChannelCapacity)
        assertEquals("149.154.167.220", c.dcRedirects[2])
        assertEquals("149.154.167.220", c.dcRedirects[4])
        assertEquals(2, c.dcOverrides[203])
    }

    @Test
    fun customWsSettings() {
        val c = ProxyConfig(
            listenHost = "0.0.0.0",
            listenPort = 1,
            secretHex = "00",
            dcRedirects = mapOf(1 to "1.1.1.1"),
            dcOverrides = emptyMap(),
            bufferSize = 4096,
            wsPingIntervalSeconds = 60L,
            wsIncomingChannelCapacity = 128,
        )
        assertEquals(60L, c.wsPingIntervalSeconds)
        assertEquals(128, c.wsIncomingChannelCapacity)
        assertTrue(c.dcOverrides.isEmpty())
    }
}
