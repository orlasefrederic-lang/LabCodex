package lab.netstrazh.reviewclient;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent ordinary app: never binds a network, protects a socket, or shares a UID. */
public final class ProbeActivity extends Activity {
    private static final String TAG = "NetStrazhReviewClient";
    private static final String HOST = "10.0.2.2";
    private static final int TIMEOUT_MS = 5000;
    private static final String PROCESS_NONCE = UUID.randomUUID().toString();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setText("Independent TCP and UDP review client");
        status.setPadding(24, 60, 24, 24);
        setContentView(status);
        submit(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        submit(intent);
    }

    private void submit(Intent intent) {
        String supplied = intent.getStringExtra("marker");
        final String marker = supplied == null ? "manual-" + SystemClock.elapsedRealtime() : supplied;
        if (!marker.matches("[A-Za-z0-9_-]{1,120}")) {
            status.setText("Marker must contain 1–120 letters, digits, underscores, or hyphens.");
            return;
        }
        WORKER.execute(() -> probe(marker));
    }

    private JSONObject network() throws Exception {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        LinkProperties lp = active == null ? null : cm.getLinkProperties(active);
        JSONArray addresses = new JSONArray();
        if (lp != null) for (LinkAddress address : lp.getLinkAddresses()) {
            addresses.put(address.getAddress().getHostAddress());
        }
        return new JSONObject().put("active", String.valueOf(active))
            .put("bound", String.valueOf(cm.getBoundNetworkForProcess()))
            .put("vpn", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            .put("linkAddresses", addresses);
    }

    private void probe(String marker) {
        try {
            JSONObject result = new JSONObject().put("marker", marker)
                .put("package", getPackageName()).put("uid", Process.myUid())
                .put("pid", Process.myPid()).put("processNonce", PROCESS_NONCE)
                .put("sequence", SEQUENCE.incrementAndGet())
                .put("startedMs", System.currentTimeMillis()).put("networkBefore", network());
            // Both calls allocate and close new sockets even when the process is reused.
            result.put("tcp", tcp(marker));
            result.put("udp", udp(marker));
            result.put("networkAfter", network()).put("finishedMs", System.currentTimeMillis());
            byte[] bytes = result.toString(2).getBytes(StandardCharsets.UTF_8);
            writeAtomic(new File(getFilesDir(), "result-" + marker + ".json"), bytes);
            writeAtomic(new File(getFilesDir(), "result.json"), bytes);
            Log.i(TAG, result.toString());
            runOnUiThread(() -> status.setText("Completed " + marker + "\nUID " + Process.myUid()
                + "\nPID " + Process.myPid() + "\nSee files/result.json"));
        } catch (Exception failure) {
            Log.e(TAG, "Failed to write probe " + marker, failure);
        }
    }

    private JSONObject tcp(String marker) throws Exception {
        String request = "GET /echo/" + marker + " HTTP/1.1\r\nHost: " + HOST
            + "\r\nConnection: close\r\n\r\n";
        JSONObject result = new JSONObject().put("host", HOST).put("port", 18080)
            .put("request", request).put("requestBytes", request.length()).put("ok", false);
        long started = SystemClock.elapsedRealtime();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName(HOST), 18080), TIMEOUT_MS);
                result.put("localAddress", socket.getLocalAddress().getHostAddress())
                    .put("localPort", socket.getLocalPort());
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                InputStream input = socket.getInputStream();
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 65536) throw new IllegalStateException("Fixture response exceeded 64 KiB");
                    if (SystemClock.elapsedRealtime() - started > 15000) {
                        throw new IllegalStateException("Fixture response exceeded deadline");
                    }
                }
                String response = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
                int boundary = response.indexOf("\r\n\r\n");
                String payload = boundary == -1 ? "" : response.substring(boundary + 4);
                result.put("response", response).put("responseBytes", bytes.size()).put("payload", payload)
                    .put("ok", response.startsWith("HTTP/1.1 200 ")
                        && payload.equals("netstrazh-http:" + marker));
            } finally {
                result.put("finalLocalAddress", String.valueOf(socket.getLocalAddress().getHostAddress()))
                    .put("finalLocalPort", socket.getLocalPort());
            }
        } catch (Exception failure) {
            result.put("error", failure.getClass().getName() + ": " + failure.getMessage());
        }
        return result.put("durationMs", SystemClock.elapsedRealtime() - started);
    }

    private JSONObject udp(String marker) throws Exception {
        String request = "ns-test:" + marker;
        byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        JSONObject result = new JSONObject().put("host", HOST).put("port", 18081)
            .put("request", request).put("requestBytes", bytes.length).put("ok", false);
        long started = SystemClock.elapsedRealtime();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(new InetSocketAddress(InetAddress.getByName(HOST), 18081));
            result.put("localAddress", socket.getLocalAddress().getHostAddress())
                .put("localPort", socket.getLocalPort());
            socket.send(new DatagramPacket(bytes, bytes.length));
            DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
            socket.receive(packet);
            String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                StandardCharsets.US_ASCII);
            result.put("response", payload).put("payload", payload).put("responseBytes", packet.getLength())
                .put("ok", payload.equals("netstrazh-udp:" + request));
        } catch (Exception failure) {
            result.put("error", failure.getClass().getName() + ": " + failure.getMessage());
        }
        return result.put("durationMs", SystemClock.elapsedRealtime() - started);
    }

    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new IllegalStateException("Cannot replace " + target.getName());
    }
}
