import lab.netstrazh.vpn.*
import java.net.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class LocalSocks(private val phaseMs: Long, private val stallFirst: Boolean): AutoCloseable {
 val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
 private val clients = CopyOnWriteArrayList<Socket>()
 private val accepted = AtomicInteger()
 init { thread(isDaemon=true, name="audit-socks") {
  while (!server.isClosed) try {
   val s=server.accept(); clients.add(s); val index=accepted.incrementAndGet()
   thread(isDaemon=true, name="audit-peer-$index") {
    try { s.use {
     s.soTimeout=10000
     val ins=s.getInputStream(); val out=s.getOutputStream()
     val greet=ins.readNBytes(3); check(greet.contentEquals(byteArrayOf(5,1,0)))
     if (stallFirst && index == 1) { Thread.sleep(8000); return@use }
     Thread.sleep(phaseMs); out.write(byteArrayOf(5,0)); out.flush()
     val req=ins.readNBytes(10); check(req.size == 10 && req[0].toInt() == 5 && req[1].toInt() == 1)
     Thread.sleep(phaseMs); out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,53)); out.flush()
     // DNS response intentionally absent: after two individually successful phases the shared budget has elapsed.
     while (ins.read() != -1) {}
    } } catch (_: Exception) {}
   }
  } catch (_: IOException) { break }
 } }
 override fun close() { server.close(); clients.forEach { it.close() } }
}
private fun upstream(port:Int) = Upstream(object:EngineHost {
 override fun protect(s:Socket)=true
 override fun protect(s:DatagramSocket)=true
 override fun ownerUid(proto:Int, local:InetSocketAddress, remote:InetSocketAddress) = -1
 override fun resolve(host:String) = InetAddress.getByName(host)
 override val selfUid=10001
}).also { it.configure(ProxyConfig(type=ProxyType.SOCKS5, host="127.0.0.1",port=port,dnsServer="198.51.100.10")) }
fun main() {
 val query=byteArrayOf(0x12,0x34,1,0,0,1,0,0,0,0,0,0,1,97,0,0,1,0,1)
 repeat(3) { i -> LocalSocks(220,false).use { s ->
  val u=upstream(s.server.localPort); val start=System.nanoTime(); var outcome="returned"
  try { u.dnsQueryOnce(InetSocketAddress("198.51.100.10",53),query,0,query.size,300) }
  catch(e:IOException) {outcome=e.javaClass.simpleName+": "+e.message}
  finally {u.close()}
  val elapsed=(System.nanoTime()-start)/1000000
  println("once attempt=${i+1} budgetMs=300 greetingDelayMs=220 connectReplyDelayMs=220 elapsedMs=$elapsed outcome=$outcome")
  check(elapsed in 250..399) { "Shared once deadline exceeded tolerance or returned too early" }
 } }
 LocalSocks(2200,true).use { s ->
  val u=upstream(s.server.localPort); val start=System.nanoTime(); var outcome="returned"
  try {u.dnsQuery(query,0,query.size)} catch(e:IOException) {outcome=e.javaClass.simpleName+": "+e.message} finally {u.close()}
  val elapsed=(System.nanoTime()-start)/1000000
  println("total advertisedBudgetMs=${Upstream.DNS_TOTAL_MS} pipeBudgetMs=${Upstream.DNS_PIPE_TIMEOUT_MS} fallbackGreetingDelayMs=2200 fallbackConnectReplyDelayMs=2200 elapsedMs=$elapsed outcome=$outcome")
  check(elapsed in 5500..6499) {"Shared total deadline exceeded tolerance or returned too early"}
 }
 println("PASS: former cumulative SOCKS greeting/CONNECT deadline overruns are fixed for numeric DNS and proxy addresses. Only loopback fixture used; no actual remote DNS connection.")
}
