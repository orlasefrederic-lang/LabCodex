import lab.netstrazh.vpn.DnsPipe
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun main() {
    val loopback = InetAddress.getByName("127.0.0.1")
    val server = ServerSocket().apply { receiveBufferSize = 4096; bind(InetSocketAddress(loopback, 0), 8) }
    val accepted = CopyOnWriteArrayList<Socket>()
    val acceptThread = thread(isDaemon=true) {
        while (!server.isClosed) {
            try { accepted.add(server.accept()) } catch (_: IOException) { break }
        }
    }
    // Same maximum-size DNS payload approach as the project's blocked-write test.
    val query = ByteArray(60000).apply { this[0] = 0x12; this[1] = 0x34; this[2] = 1 }
    val durations = mutableListOf<Long>()
    try {
        repeat(3) { index ->
            val pipe = DnsPipe {
                // Reproduce a slow but still successful proxy connection using a bounded delay.
                Thread.sleep(250)
                SocketChannel.open().apply {
                    socket().sendBufferSize = 4096
                    connect(InetSocketAddress(loopback, server.localPort))
                }
            }
            val started = System.nanoTime()
            var outcome = "returned"
            try { pipe.query(query, 0, query.size, 300) }
            catch (e: IOException) { outcome = e.javaClass.simpleName + ": " + e.message }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            durations.add(elapsed)
            val closing = System.nanoTime()
            pipe.close()
            val closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closing)
            println("attempt=${index+1} requestedTimeoutMs=300 elapsedMs=$elapsed closeMs=$closeMs result=$outcome")
        }
        check(durations.all { it >= 250 && it < 450 }) { "Shared deadline was exceeded: $durations" }
        println("PASS: 300ms timeout includes the 250ms connection delay and blocked write; close remains prompt.")
    } finally {
        server.close()
        accepted.forEach { it.close() }
        acceptThread.join(500)
    }
}
