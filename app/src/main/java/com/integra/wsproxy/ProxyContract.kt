package com.integra.wsproxy

object ProxyIntents {
    const val ACTION_START = "com.integra.wsproxy.action.START"
    const val ACTION_STOP = "com.integra.wsproxy.action.STOP"
    const val ACTION_OPEN_TELEGRAM = "com.integra.wsproxy.action.OPEN_TELEGRAM"
    const val ACTION_STATE_CHANGED = "com.integra.wsproxy.action.STATE_CHANGED"

    const val EXTRA_RUNNING = "running"
}

object ProxyNotification {
    const val CHANNEL_ID = "tg_ws_proxy_v2"
    const val NOTIFICATION_ID = 1001
}

