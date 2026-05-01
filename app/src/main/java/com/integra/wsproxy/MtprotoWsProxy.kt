package com.integra.wsproxy

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
class MtprotoWsProxy(
    private val cfg: ProxyConfig,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rnd = SecureRandom()
    private var server: ServerSocket? = null

    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    fun run() {
        val ss = ServerSocket(cfg.listenPort, 50, InetAddress.getByName(cfg.listenHost))
        server = ss

        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (e: Exception) {
                if (!running.get()) break
                continue
            }
            scope.launch { handleClient(sock) }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        scope.cancel()
    }

    private fun handleClient(sock: Socket) {
        sock.tcpNoDelay = true
        sock.receiveBufferSize = cfg.bufferSize
        sock.sendBufferSize = cfg.bufferSize

        sock.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val secret = hexToBytes(cfg.secretHex)
            val handshake = input.readExact(HANDSHAKE_LEN) ?: return

            val parsed = tryHandshake(handshake, secret) ?: return
            val (dcId, isMedia, protoTag, clientDecPrekeyIv) = parsed
            val dcIdx = if (isMedia) -dcId else dcId

            val relayInit = generateRelayInit(protoTag, dcIdx)
            val ctx = buildCryptoCtx(clientDecPrekeyIv, secret, relayInit)

            val targetIp = cfg.dcRedirects[dcId] ?: return
            val domains = wsDomains(dcId, isMedia)

            val conn = connectWs(targetIp, domains) ?: return

            conn.ws.send(ByteString.of(*relayInit))

            bridge(input, output, conn, ctx)
        }
    }

    private fun wsDomains(dcId: Int, isMedia: Boolean): List<String> {
        val dc = cfg.dcOverrides[dcId] ?: dcId
        return if (isMedia) {
            listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org")
        } else {
            listOf("kws$dc.web.telegram.org", "kws$dc-1.web.telegram.org")
        }
    }

    private data class WsConn(
        val ws: WebSocket,
        val incoming: Channel<ByteArray>,
    )

    private fun connectWs(targetIp: String, domains: List<String>): WsConn? {
        for (domain in domains) {
            val url = "wss://$domain/apiws"
            val client = http.newBuilder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname.equals(domain, ignoreCase = true)) {
                            listOf(InetAddress.getByName(targetIp))
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                })
                .build()

            val req = Request.Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", "binary")
                .build()

            val latch = java.util.concurrent.CountDownLatch(1)
            val incoming = Channel<ByteArray>(capacity = Channel.BUFFERED)
            var ok = false

            val created = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ok = true
                    latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    incoming.trySend(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    incoming.close(t)
                    latch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.close()
                    webSocket.close(code, reason)
                }
            })

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (ok) return WsConn(created, incoming)
            created.cancel()
            incoming.close()
        }
        return null
    }

    private fun bridge(
        clientIn: InputStream,
        clientOut: OutputStream,
        conn: WsConn,
        ctx: CryptoCtx,
    ) {
        val closed = AtomicBoolean(false)
        val jobs = mutableListOf<Job>()

        // WS -> client
        jobs += scope.launch {
            try {
                for (frame in conn.incoming) {
                    if (closed.get()) break
                    val plain = ctx.tgDecrypt.update(frame)
                    val enc = ctx.clientEnc.update(plain)
                    clientOut.write(enc)
                    clientOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                closed.set(true)
            }
        }

        // client -> WS
        jobs += scope.launch {
            val buf = ByteArray(16 * 1024)
            while (!closed.get()) {
                val n = try {
                    clientIn.read(buf)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                val plain = ctx.clientDec.update(chunk)
                val enc = ctx.tgEnc.update(plain)
                if (!conn.ws.send(ByteString.of(*enc))) break
            }
            closed.set(true)
        }

        runBlocking {
            jobs.forEach { j ->
                try {
                    j.join()
                } catch (_: CancellationException) {
                }
            }
        }
        runCatching { conn.ws.close(1000, "bye") }
        conn.incoming.close()
    }

    private fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeParsed? {
        val prekeyIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val prekey = prekeyIv.copyOfRange(0, PREKEY_LEN)
        val iv = prekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val decKey = sha256(prekey + secret)
        val dec = aesCtr().apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(iv))
        }
        val decrypted = dec.update(handshake)

        val protoTag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        if (!protoTag.contentEquals(PROTO_TAG_ABRIDGED) &&
            !protoTag.contentEquals(PROTO_TAG_INTERMEDIATE) &&
            !protoTag.contentEquals(PROTO_TAG_SECURE)
        ) return null

        val dcIdxBytes = decrypted.copyOfRange(DC_IDX_POS, DC_IDX_POS + 2)
        val dcIdx = (dcIdxBytes[0].toInt() and 0xFF) or ((dcIdxBytes[1].toInt()) shl 8)
        val signedDcIdx = dcIdx.toShort().toInt()
        val dcId = abs(signedDcIdx)
        val isMedia = signedDcIdx < 0
        return HandshakeParsed(dcId, isMedia, protoTag, prekeyIv)
    }

    private fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        while (true) {
            val rndBytes = ByteArray(HANDSHAKE_LEN).also { rnd.nextBytes(it) }
            val b0 = rndBytes[0].toInt() and 0xFF
            if (RESERVED_FIRST_BYTES.contains(b0)) continue
            val start4 = rndBytes.copyOfRange(0, 4)
            if (RESERVED_STARTS.any { it.contentEquals(start4) }) continue
            if (rndBytes.copyOfRange(4, 8).contentEquals(RESERVED_CONTINUE)) continue

            val encKey = rndBytes.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
            val encIv = rndBytes.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
            val cipher = aesCtr().apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))
            }

            val dcBytes = byteArrayOf(
                (dcIdx and 0xFF).toByte(),
                ((dcIdx shr 8) and 0xFF).toByte(),
            )
            val tailPlain = protoTag + dcBytes + ByteArray(2).also { rnd.nextBytes(it) }

            val encryptedFull = cipher.update(rndBytes)
            val keystreamTail = ByteArray(8) { i ->
                (encryptedFull[56 + i].toInt() xor rndBytes[56 + i].toInt()).toByte()
            }
            val encryptedTail = ByteArray(8) { i ->
                (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
            }

            val result = rndBytes.clone()
            System.arraycopy(encryptedTail, 0, result, PROTO_TAG_POS, 8)
            return result
        }
    }

    private fun buildCryptoCtx(clientDecPrekeyIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val clientDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val clientDecKey = sha256(clientDecPrekey + secret)

        val clientEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val clientEncKey = sha256(clientEncPrekeyIv.copyOfRange(0, PREKEY_LEN) + secret)
        val clientEncIv = clientEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val clientDec = aesCtr().apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(clientDecKey, "AES"), IvParameterSpec(clientDecIv))
            update(ZERO_64)
        }
        val clientEnc = aesCtr().apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(clientEncKey, "AES"), IvParameterSpec(clientEncIv))
        }

        val relayEncKey = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)

        val relayDecPrekeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN).reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)

        val tgEnc = aesCtr().apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayEncKey, "AES"), IvParameterSpec(relayEncIv))
            update(ZERO_64)
        }
        val tgDec = aesCtr().apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(relayDecKey, "AES"), IvParameterSpec(relayDecIv))
        }

        return CryptoCtx(clientDec, clientEnc, tgEnc, tgDec)
    }

    private fun aesCtr(): Cipher {
        return Cipher.getInstance("AES/CTR/NoPadding")
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val j = i * 2
            out[i] = clean.substring(j, j + 2).toInt(16).toByte()
        }
        return out
    }

    private fun InputStream.readExact(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r <= 0) return null
            off += r
        }
        return buf
    }

    private data class HandshakeParsed(
        val dcId: Int,
        val isMedia: Boolean,
        val protoTag: ByteArray,
        val clientDecPrekeyIv: ByteArray,
    )

    private data class CryptoCtx(
        val clientDec: Cipher,
        val clientEnc: Cipher,
        val tgEnc: Cipher,
        val tgDecrypt: Cipher,
    )

    private companion object {
        const val HANDSHAKE_LEN = 64
        const val SKIP_LEN = 8
        const val PREKEY_LEN = 32
        const val KEY_LEN = 32
        const val IV_LEN = 16
        const val PROTO_TAG_POS = 56
        const val DC_IDX_POS = 60

        val ZERO_64 = ByteArray(64)

        val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
        val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
        val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

        val RESERVED_FIRST_BYTES = setOf(0xEF)
        val RESERVED_STARTS = listOf(
            byteArrayOf(0x48, 0x45, 0x41, 0x44), // HEAD
            byteArrayOf(0x50, 0x4F, 0x53, 0x54), // POST
            byteArrayOf(0x47, 0x45, 0x54, 0x20), // GET<space>
            byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
            byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
            byteArrayOf(0x16, 0x03, 0x01, 0x02), // TLS-ish
        )
        val RESERVED_CONTINUE = byteArrayOf(0x00, 0x00, 0x00, 0x00)
    }
}

