import lab.netstrazh.vpn.*
import java.net.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private val query=byteArrayOf(0x12,0x34,1,0,0,1,0,0,0,0,0,0,1,97,0,0,1,0,1)
private val target=InetSocketAddress("198.51.100.10",53)

private class AuditHost(private val slowName:String?, private val resolveDelayMs:Long=0, private val protectDelayMs:Long=0):EngineHost {
 val sockets=CopyOnWriteArrayList<Socket>()
 val resolves=AtomicInteger()
 override fun protect(s:Socket):Boolean { sockets.add(s); if(protectDelayMs>0) Thread.sleep(protectDelayMs); return true }
 override fun protect(s:DatagramSocket)=true
 override fun ownerUid(proto:Int,local:InetSocketAddress,remote:InetSocketAddress)=-1
 override fun resolve(host:String):InetAddress {
  if(host==slowName) { resolves.incrementAndGet(); Thread.sleep(resolveDelayMs) }
  return InetAddress.getByAddress(if(host=="slow-dns.invalid") byteArrayOf(198.toByte(),51,100,10) else byteArrayOf(127,0,0,1))
 }
 override val selfUid=10001
 fun closeSockets() { sockets.forEach { try {it.close()}catch(_:Exception){} } }
}
fun main() {
 // The Android resolver/protect boundary is deliberately delayed; production Upstream is unchanged.
 // No hostname is sent to external DNS and no connection ever leaves loopback.
 for ((label,host,proxyHost) in listOf(
  Triple("slow-proxy-name",AuditHost("slow-proxy.invalid",250),"slow-proxy.invalid"),
  Triple("deadline-expires-during-protect",AuditHost(null,protectDelayMs=250),"127.0.0.1")
 )) {
  ServerSocket(0,16,InetAddress.getByName("127.0.0.1")).use { listener ->
   val u=Upstream(host);u.configure(ProxyConfig(type=ProxyType.SOCKS5,host=proxyHost,port=listener.localPort,dnsServer="198.51.100.10"))
   val started=System.nanoTime();var outcome="returned"
   try { u.dnsQueryOnce(target,query,0,query.size,50) } catch(e:IOException) {outcome=e.javaClass.simpleName+": "+e.message}
   val elapsed=(System.nanoTime()-started)/1_000_000
   val openBefore=host.sockets.count{it.channel?.isOpen==true}
   u.close()
   val openAfter=host.sockets.count{it.channel?.isOpen==true}
   println("case=$label budgetMs=50 boundaryDelayMs=250 elapsedMs=$elapsed createdSockets=${host.sockets.size} openAfterFailure=$openBefore openAfterUpstreamClose=$openAfter outcome=$outcome")
   check(elapsed>=240 && host.sockets.size==1 && openBefore==1 && openAfter==1)
   // Keep captured references only to inspect deterministic cleanup, then close immediately.
   host.closeSockets();check(host.sockets.all{it.channel?.isOpen==false})
  }
 }
 val host=AuditHost("slow-dns.invalid",4000)
 ServerSocket(0,16,InetAddress.getByName("127.0.0.1")).use { listener ->
  val u=Upstream(host);u.configure(ProxyConfig(type=ProxyType.SOCKS5,host="127.0.0.1",port=listener.localPort,dnsServer="slow-dns.invalid"))
  val started=System.nanoTime();var outcome="returned"
  try {u.dnsQuery(query,0,query.size)} catch(e:IOException){outcome=e.javaClass.simpleName+": "+e.message}
  val elapsed=(System.nanoTime()-started)/1_000_000
  u.close();host.closeSockets()
  println("case=slow-dns-name totalBudgetMs=${Upstream.DNS_TOTAL_MS} eachResolveDelayMs=4000 resolveCalls=${host.resolves.get()} elapsedMs=$elapsed outcome=$outcome")
  check(host.resolves.get()==2 && elapsed>=6900 && elapsed<8500)
 }
 println("CONFIRMED: synchronous host.resolve can exceed caller DNS deadline; a deadline thrown after newChannel but before its try/catch leaves that channel open at return. These are bounded boundary-delay scenarios, not a measured Android resolver failure or permanent descriptor exhaustion.")
}
