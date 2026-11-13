package com.eevdf.capabilities.multidevicesync.logic

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live link to the other phone: one connection that stays open while the
 * app is on screen on both phones, and runs a [DeviceExchange.round] whenever
 * either phone has something new.
 *
 * WHY: opening a Bluetooth connection for every exchange, on a timer, meant a
 * change took up to a minute to arrive. Now a timer start on one phone is a
 * round a fraction of a second later — no connect, no wait.
 *
 * ── How a round starts ────────────────────────────────────────────────────────
 *
 *  - right after the handshake (both sides, no start byte);
 *  - when this side calls [requestRound]: it sends a start byte and runs its
 *    side of the round;
 *  - when the start byte arrives from the other side.
 *
 * Both phones may start a round at the same moment. That needs no special
 * case: the roles in a round are fixed for the life of the link (the phone
 * that dialled is the "initiator"), and each phone's start byte simply arrives
 * where the other expected the round's first message, where it is skipped.
 *
 * Requests that arrive while a round is running are folded into ONE more round
 * afterwards, so a burst of saves costs at most two rounds.
 *
 * ── Threads ──────────────────────────────────────────────────────────────────
 *
 * [run] is the only thread that reads protocol messages or writes anything.
 * A pump thread only moves bytes from the link into a queue, which lets [run]
 * wait for "the other phone said something" OR "this phone wants a round" at
 * the same time — something a plain blocking read cannot do.
 */
internal class DeviceSession(
    private val exchange: DeviceExchange,
    /** True on the phone that dialled. Fixes each side's role in every round. */
    val initiator: Boolean,
    input: InputStream,
    output: OutputStream,
    /** Closes the underlying link; unblocks everything. */
    private val closeLink: () -> Unit,
    /** After the handshake: return false to drop this link (another one to the same phone is preferred). */
    private val onConnected: (DeviceExchange.Peer) -> Boolean = { true },
    /** Wraps each round; the app takes its sync lock here so a round never overlaps a local sync. */
    private val aroundRound: (() -> DeviceExchange.Result) -> DeviceExchange.Result = { it() },
    private val onRound: (DeviceExchange.Result) -> Unit = {},
    private val keepAliveMs: Long = 10_000L,
) {
    private val pumped = PumpedInput(input)
    private val inp = DataInputStream(pumped)
    private val out = DataOutputStream(BufferedOutputStream(output, DeviceExchange.CHUNK * 2))
    private val wanted = AtomicBoolean(false)
    @Volatile private var closing = false

    @Volatile var peer: DeviceExchange.Peer? = null
        private set

    /** Rounds completed on this link. */
    @Volatile var rounds = 0
        private set

    /** Asks for a round as soon as possible. Any thread; cheap; requests coalesce. */
    fun requestRound() {
        wanted.set(true)
        pumped.wake()
    }

    /** Ends the link. Any thread. A round in progress is cut off and resumes on the next link. */
    fun close() {
        closing = true
        pumped.wake()
        try { closeLink() } catch (_: Exception) { }
    }

    val isOpen: Boolean get() = !closing

    /** Runs until the link ends. Throws if it ends for any reason other than [close]. */
    @Throws(IOException::class)
    fun run() {
        try {
            val p = exchange.handshake(initiator, inp, out)
            peer = p
            if (!onConnected(p)) return
            doRound(p, sendStart = false)
            while (!closing) {
                when (pumped.awaitActivity(keepAliveMs)) {
                    PumpedInput.Activity.DATA -> handleIncoming(p)
                    PumpedInput.Activity.TIMEOUT -> { out.writeByte(DeviceExchange.T_PING.toInt()); out.flush() }
                    PumpedInput.Activity.WAKE -> Unit
                    PumpedInput.Activity.CLOSED -> throw pumped.failure()
                }
                if (!closing && wanted.getAndSet(false)) doRound(p, sendStart = true)
            }
        } catch (e: IOException) {
            if (!closing) throw e
        } finally {
            closing = true
            try { closeLink() } catch (_: Exception) { }
        }
    }

    private fun handleIncoming(p: DeviceExchange.Peer) {
        when (pumped.peek().toByte()) {
            DeviceExchange.T_ROUND -> { inp.readByte(); doRound(p, sendStart = false) }
            DeviceExchange.T_PING -> inp.readByte()
            // The other side began a round without a start byte: its first message is already here.
            DeviceExchange.T_INVENTORY_PUBLIC -> doRound(p, sendStart = false)
            else -> throw DeviceExchange.ProtocolException("unexpected message between rounds")
        }
    }

    private fun doRound(p: DeviceExchange.Peer, sendStart: Boolean) {
        val result = aroundRound {
            if (sendStart) { out.writeByte(DeviceExchange.T_ROUND.toInt()); out.flush() }
            // Anything written to the folder from here on is not in this round's
            // file list, so a request made from now on must lead to another round.
            exchange.round(initiator, inp, out, p, onListed = { wanted.set(false) })
        }
        rounds++
        onRound(result)
    }
}

/**
 * An InputStream fed by its own thread, so the reader can also be woken for
 * reasons that are not bytes ([wake]) and can wait with a timeout.
 */
internal class PumpedInput(private val source: InputStream) : InputStream() {

    enum class Activity { DATA, WAKE, TIMEOUT, CLOSED }

    private object Wake
    private object Closed

    private val queue = LinkedBlockingQueue<Any>()
    private var current: ByteArray? = null
    private var pos = 0
    @Volatile private var error: IOException? = null

    init {
        Thread({
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n > 0) queue.put(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                error = e
            } finally {
                queue.put(Closed)
            }
        }, "eevdf-link-reader").apply { isDaemon = true; start() }
    }

    fun wake() { queue.put(Wake) }

    fun failure(): IOException = error ?: IOException("the other phone closed the link")

    private fun hasBytes(): Boolean = current?.let { pos < it.size } == true

    /** Waits until bytes arrive, [wake] is called, the link closes, or [timeoutMs] passes. */
    fun awaitActivity(timeoutMs: Long): Activity {
        if (hasBytes()) return Activity.DATA
        return when (val item = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)) {
            null -> Activity.TIMEOUT
            Wake -> Activity.WAKE
            Closed -> { queue.put(Closed); Activity.CLOSED }
            is ByteArray -> { current = item; pos = 0; Activity.DATA }
            else -> Activity.WAKE
        }
    }

    /** Blocks until a byte is available; wake-ups are ignored here. */
    private fun fill() {
        while (!hasBytes()) {
            when (val item = queue.take()) {
                is ByteArray -> { current = item; pos = 0 }
                Closed -> { queue.put(Closed); throw failure() }
                else -> Unit
            }
        }
    }

    /** The next byte, without consuming it. */
    fun peek(): Int { fill(); return current!![pos].toInt() and 0xff }

    override fun read(): Int {
        fill()
        return current!![pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        fill()
        val c = current!!
        val n = minOf(len, c.size - pos)
        System.arraycopy(c, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = current?.let { it.size - pos } ?: 0
}
