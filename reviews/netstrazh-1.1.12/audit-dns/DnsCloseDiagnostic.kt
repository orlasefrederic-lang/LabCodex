import lab.netstrazh.vpn.DnsPipe
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

fun main() {
    val loopback = InetAddress.getByName("127.0.0.1")
    val server = ServerSocket().apply { receiveBufferSize = 4096; bind(InetSocketAddress(loopback, 0), 8) }
    val peers = CopyOnWriteArrayList<Socket>()
    val channels = CopyOnWriteArrayList<SocketChannel>()
    val opens = AtomicInteger()
    val accept = thread(isDaemon=true) {
        while (!server.isClosed) try { peers.add(server.accept()) } catch (_: IOException) { break }
    }
    val pipe = DnsPipe {
        opens.incrementAndGet()
        SocketChannel.open().apply {
            socket().sendBufferSize = 4096
            connect(InetSocketAddress(loopback, server.localPort))
            channels.add(this)
        }
    }
    val q = ByteArray(60000).apply { this[0] = 0x12; this[1] = 0x34; this[2] = 1 }
    val failure = AtomicReference<Throwable>()
    val started = System.nanoTime()
    val worker = thread(isDaemon=true, name="audit-close-diagnostic") {
        try { pipe.query(q, 0, q.size, 2000) } catch (e: Throwable) { failure.set(e) }
    }
    try {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        var writing = false
        while (System.nanoTime() < limit) {
            writing = worker.stackTrace.any { it.className.contains("Socket") && it.methodName.startsWith("write") }
            if (writing) break
            Thread.sleep(5)
        }
        check(writing) { "Did not observe socket write" }
        val before = System.nanoTime()
        pipe.close()
        val closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        Thread.sleep(100)
        println("afterClose closeMs=$closeMs opens=${opens.get()} firstChannelOpen=${channels.first().isOpen} workerAlive=${worker.isAlive}")
        println("workerStack=" + worker.stackTrace.take(9).joinToString(" | "))
        worker.join(3000)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        println("final elapsedMs=$elapsedMs opens=${opens.get()} workerAlive=${worker.isAlive} failure=${failure.get()?.javaClass?.simpleName}:${failure.get()?.message}")
        check(closeMs < 300 && !channels.first().isOpen && !worker.isAlive && failure.get() is IOException)
        check(opens.get() == 2) { "Expected close-triggered retry, got ${opens.get()}" }
        println("CONFIRMED: close promptly closes original channel; query retries once on a fresh channel and eventually hits its bounded deadline.")
    } finally {
        pipe.close()
        channels.forEach { it.close() }
        peers.forEach { it.close() }
        server.close()
        accept.join(300)
    }
}
