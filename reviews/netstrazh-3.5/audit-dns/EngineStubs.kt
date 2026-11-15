package lab.netstrazh.vpn
import java.io.Closeable
import java.net.*
// Android-independent host boundary only. Actual Upstream, DnsPipe and SOCKS code are compiled unchanged.
interface EngineHost {
 fun protect(s: Socket): Boolean
 fun protect(s: DatagramSocket): Boolean
 fun ownerUid(proto: Int, local: InetSocketAddress, remote: InetSocketAddress): Int
 fun resolve(host: String): InetAddress
 val selfUid: Int
 fun onProxyApp(uid: Int) {}
 fun proxyAppLabel(): String? = null
}
object Engine {
 fun closeQuietly(c: Closeable?) { try { c?.close() } catch (_: Exception) {} }
 // Identical predicate from source Engine.kt; TEST-NET destination is treated as nonlocal.
 fun isLocal(a: InetAddress): Boolean {
  val b = a.address
  return a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || a.isAnyLocalAddress ||
   a.isMulticastAddress || (b.size == 4 && b[0].toInt() == 100 && (b[1].toInt() and 0xC0) == 64) ||
   (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC)
 }
}
