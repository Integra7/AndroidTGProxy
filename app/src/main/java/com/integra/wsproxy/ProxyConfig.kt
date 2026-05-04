package com.integra.wsproxy

data class ProxyConfig(
    val listenHost: String,
    val listenPort: Int,
    val secretHex: String,
    val dcRedirects: Map<Int, String>,
    val dcOverrides: Map<Int, Int>,
    val bufferSize: Int,
    /** WebSocket RFC ping from client; keeps NAT sessions alive. */
    val wsPingIntervalSeconds: Long = 25L,
    /** Buffered frames WS → MTProto; on overflow the session closes cleanly instead of corrupting. */
    val wsIncomingChannelCapacity: Int = 512,
) {
    companion object {
        fun default(): ProxyConfig = ProxyConfig(
            listenHost = "127.0.0.1",
            listenPort = 1443,
            secretHex = "3075abe65830f0325116bb0416cadf9f",
            dcRedirects = mapOf(
                2 to "149.154.167.220",
                4 to "149.154.167.220",
            ),
            dcOverrides = mapOf(
                203 to 2,
            ),
            bufferSize = 256 * 1024,
        )
    }
}

