import lab.netstrazh.vpn.*
import java.net.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Local-only integration of unchanged Upstream/SOCKS code; no connection to the requested target is made. */
fun main() {
 val loopback=InetAddress.getByName("127.0.0.1")
 val server=ServerSocket(0,1,loopback)
 val serverOutcome=CompletableFuture<String>()
 val expected="netstrazh-v115-slow-connect-exact-payload".toByteArray(Charsets.US_ASCII)
 val reply="local-fixture-reply:".toByteArray(Charsets.US_ASCII)+expected
 val connectDelayMs=7500L
 val worker=thread(isDaemon=true,name="audit-slow-connect") {
  try {
   server.accept().use { s ->
    s.soTimeout=15000
    val ins=s.getInputStream(); val out=s.getOutputStream()
    check(ins.readNBytes(3).contentEquals(byteArrayOf(5,1,0))) {"Unexpected SOCKS greeting"}
    out.write(byteArrayOf(5,0)); out.flush() // Immediate method selection, before any remote work.
    val req=ins.readNBytes(10)
    val expectedRequest=byteArrayOf(5,1,0,1,198.toByte(),51,100,10,1,187.toByte()) // TEST-NET:443
    check(req.contentEquals(expectedRequest)) {"Unexpected SOCKS CONNECT destination"}
    Thread.sleep(connectDelayMs)
    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1)); out.flush()
    check(ins.readNBytes(expected.size).contentEquals(expected)) {"Payload mismatch at fixture"}
    out.write(reply); out.flush()
    serverOutcome.complete("exact CONNECT target and payload verified")
   }
  } catch(e:Throwable) {serverOutcome.completeExceptionally(e)}
 }
 val upstream=Upstream(object:EngineHost {
  override fun protect(s:Socket)=true
  override fun protect(s:DatagramSocket)=true
  override fun ownerUid(proto:Int,local:InetSocketAddress,remote:InetSocketAddress) = -1
  override fun resolve(host:String)=InetAddress.getByName(host)
  override val selfUid=10001
 }).also {it.configure(ProxyConfig(type=ProxyType.SOCKS5,host="127.0.0.1",port=server.localPort,dnsViaProxy=false))}
 try {
  val start=System.nanoTime()
  val opened=upstream.openTcp(InetSocketAddress("198.51.100.10",443),10005,15000) {}
  val openMs=(System.nanoTime()-start)/1_000_000
  opened.ch.use { ch ->
   check(opened.viaProxy) {"Unexpected direct fallback"}
   val bytes=ByteBuffer.wrap(expected)
   while(bytes.hasRemaining()) ch.write(bytes)
   ch.socket().soTimeout=5000
   val actual=ch.socket().getInputStream().readNBytes(reply.size)
   check(actual.contentEquals(reply)) {"Response payload mismatch"}
  }
  val events=upstream.recentEvents().map {it.msg}
  check(openMs>=7000 && openMs<15000) {"Unexpected CONNECT elapsed time: $openMs"}
  check(events.none {it.contains("не отвечает") || it.contains("усыпила") || it.contains("зависло")}) {"Slow CONNECT falsely labeled as silent proxy: $events"}
  check(upstream.downAt==0L) {"Working proxy marked down"}
  println("greeting=immediate connectReplyDelayMs=$connectDelayMs localGreetingLimitMs=${Upstream.LOCAL_HANDSHAKE_MS} connectLimitMs=${Upstream.HANDSHAKE_MS} openElapsedMs=$openMs viaProxy=${opened.viaProxy}")
  println("requestBytes=${expected.size} responseBytes=${reply.size} exactPayload=true server=${serverOutcome.get(3,TimeUnit.SECONDS)}")
  println("proxyEvents=$events downAt=${upstream.downAt} lastError=${upstream.lastError}")
  println("PASS: SOCKS CONNECT delayed beyond 6 seconds succeeds after immediate greeting, exchanges exact payload, and is not reported as a sleeping/frozen proxy. All traffic used a loopback fixture; no remote target contacted.")
 } finally {
  upstream.close();server.close();worker.join(500)
 }
}
