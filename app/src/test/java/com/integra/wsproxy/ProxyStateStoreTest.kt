package com.integra.wsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyStateStoreTest {

    private lateinit var app: Context

    @Before
    fun clearPrefs() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("tg_ws_proxy", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun running_defaultsFalse_roundTrip() {
        val store = ProxyStateStore.get(app)
        assertFalse(store.isRunning())
        store.setRunning(true)
        assertTrue(store.isRunning())
        store.setRunning(false)
        assertFalse(store.isRunning())
    }
}
