package com.integra.wsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyConfigStoreTest {

    private lateinit var app: Context

    @Before
    fun clearPrefs() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("tg_ws_proxy", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun load_defaultsAndPersistsGeneratedSecret() {
        val store = ProxyConfigStore.get(app)
        val first = store.load()
        assertEquals("127.0.0.1", first.listenHost)
        assertEquals(1443, first.listenPort)
        assertEquals(32, first.secretHex.length)
        assertTrue(first.secretHex.all { it in '0'..'9' || it in 'a'..'f' })

        val second = store.load()
        assertEquals(first.secretHex, second.secretHex)
    }

    @Test
    fun tgProxyLink_format() {
        val store = ProxyConfigStore.get(app)
        val cfg = ProxyConfig(
            listenHost = "10.0.0.2",
            listenPort = 8888,
            secretHex = "abcd",
            dcRedirects = mapOf(2 to "1.1.1.1"),
            dcOverrides = emptyMap(),
            bufferSize = 8192,
        )
        val link = store.tgProxyLink(cfg)
        assertTrue(link.startsWith("tg://proxy?"))
        assertTrue(link.contains("server=10.0.0.2"))
        assertTrue(link.contains("port=8888"))
        assertTrue(link.contains("secret=ddabcd"))
    }
}
