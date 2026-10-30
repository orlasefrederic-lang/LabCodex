import lab.netstrazh.vpn.DnsPipe
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

fun main() {
    val loopback = InetAddress.getByName("127.0.0.1")
    val server = ServerSocket().apply { receiveBufferSize = 4096; bind(InetSocketAddress(loopback, 0), 8) }
    val peers = CopyOnWriteArrayList<Socket>()
    val channels = CopyOnWriteArrayList<SocketChannel>()
    val accept = thread(isDaemon=true) {
        while (!server.isClosed) try { peers.add(server.accept()) } catch (_: IOException) { break }
    }
    val pipe = DnsPipe {
        SocketChannel.open().apply {
            socket().sendBufferSize = 4096
            connect(InetSocketAddress(loopback, server.localPort))
            channels.add(this)
        }
    }
    val q = ByteArray(60000).apply { this[0] = 0x12; this[1] = 0x34; this[2] = 1 }
    val firstFailure = AtomicReference<Throwable>()
    val first = thread(isDaemon=true, name="audit-first-writer") {
        try { pipe.query(q, 0, q.size, 1200) } catch (e: Throwable) { firstFailure.set(e) }
    }
    try {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        var writing = false
        while (System.nanoTime() < limit) {
            writing = first.stackTrace.any { it.className.contains("Socket") && it.methodName.startsWith("write") }
            if (writing) break
            Thread.sleep(5)
        }
        check(writing) { "Did not observe first socket write" }
        val secondFailure = AtomicReference<Throwable>()
        val finished = java.util.concurrent.atomic.AtomicLong()
        val started = System.nanoTime()
        val second = thread(isDaemon=true, name="audit-second-writer") {
            try { pipe.query(q, 0, 12, 100) } catch (e: Throwable) { secondFailure.set(e) } finally { finished.set(System.nanoTime()) }
        }
        Thread.sleep(150)
        println("after150ms secondAlive=${second.isAlive} secondState=${second.state} secondStack=" + second.stackTrace.take(4).joinToString(" | "))
        second.join(2500)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(finished.get() - started)
        first.join(500)
        println("second requestedTimeoutMs=100 elapsedMs=$elapsedMs finished=${!second.isAlive} failure=${secondFailure.get()?.javaClass?.simpleName}:${secondFailure.get()?.message}; firstFinished=${!first.isAlive}")
        check(!second.isAlive && elapsedMs >= 75 && elapsedMs < 400 && secondFailure.get() is IOException)
        println("PASS: 100ms query ends within its own deadline while another query holds the write lock with a 1200ms budget.")
    } finally {
        pipe.close()
        channels.forEach { it.close() }
        peers.forEach { it.close() }
        server.close()
        accept.join(300)
    }
}
