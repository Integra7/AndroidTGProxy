package com.integra.wsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyContractTest {

    @Test
    fun proxyIntents_actionsAreUniqueAndPrefixed() {
        val actions = setOf(
            ProxyIntents.ACTION_START,
            ProxyIntents.ACTION_STOP,
            ProxyIntents.ACTION_OPEN_TELEGRAM,
            ProxyIntents.ACTION_STATE_CHANGED,
        )
        assertEquals(4, actions.size)
        actions.forEach { assertTrue(it.startsWith("com.integra.wsproxy.action.")) }
    }

    @Test
    fun proxyNotification_idsStable() {
        assertEquals("tg_ws_proxy_v2", ProxyNotification.CHANNEL_ID)
        assertEquals(1001, ProxyNotification.NOTIFICATION_ID)
    }
}
