# Independent client probe

This ordinary debuggable application has package `lab.netstrazh.reviewclient` and its own UID. It opens one new TCP socket to `10.0.2.2:18080` and one new UDP socket to `10.0.2.2:18081` per launch marker. It never binds a process to a network or protects sockets. Repeated activity launches keep the process and its nonce while allocating fresh sockets.

Build without Gradle:

```sh
bash /workspace/netstrazh-v1.1.15-review/client-probe/build.sh
```

Output: `client-probe/review-client.apk`. A disposable local signing key is generated only under `client-probe/signing/`. No existing signing material is used.

After installing this APK and the review app/test APKs on the dedicated emulator, run the instrumentation class `lab.netstrazh.IndependentClientTest`. The class uses the existing `RuntimeFixture`, starts the VPN with `ProxyConfig.OFF`, probes the initial state, switches the VPN off, probes the physical network, switches it on, and probes again. All three probes must have the same PID/process nonce; each VPN phase requires the VPN source address, fresh helper UID capture records, and fresh byte/connection counters for both protocols. The helper UID must differ from the instrumentation/VPN UID.

Manual launch commands for the root review runner (not executed by this build script):

```sh
adb install -r /workspace/netstrazh-v1.1.15-review/client-probe/review-client.apk
adb shell am start -W -n lab.netstrazh.reviewclient/.ProbeActivity --es marker manual-001
adb shell run-as lab.netstrazh.reviewclient cat files/result.json
```

The atomic report is `files/result.json`; each marker also retains `files/result-MARKER.json`. Reports contain exact request/response data, local addresses/ports, UID, PID, process nonce, launch sequence, network snapshots, and timings. The only destinations are the dedicated local HTTP/UDP fixture. Socket connect/read timeouts are 5 seconds; report/capture polling is bounded at 30 seconds. The instrumentation report is written to the review app's `files/independent-client-v112.txt`.
