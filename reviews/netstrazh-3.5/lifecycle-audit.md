# NetStrazh 3.5: VPN lifecycle, notification and proxy review

Reviewed immutable archive sources at commit `dd365f008db6563365a72d5aa5448f72af001dd6`, archive SHA-256 `74df510ee434a54ad6bb04db054d565d283c6d673e259afa9df3e9115663b25f`. Comparison baseline: the archived 2.0 sources, not the loose project on the branch.

This document records **static source findings**. It does not claim Android runtime tests passed. The main review records those separately. All source references below are relative to `Lab-claude-confident-cray-n5kkx8/NetStrazh/app/src/main/java/lab/netstrazh/`.

## Confirmed improvements

1. **The premature “protection on” notification finding from 2.0 is fixed in source.** `vpn/FirewallService.kt:383–387` now selects `notif_checking` when no engine exists or its capture has not been verified. `:189–193` refreshes the notification on the first successful probe, including the ordinary successful start (previously only recovery from `captureBroken` triggered this refresh). The start path updates the notification after assigning the new engine (`:137–144`). This brings the notification into line with the existing pending state in `ui/MainActivity.kt:160–162`.

2. **Changing Android's lockdown setting now triggers a check of the proxy exclusion.** The running service rechecks on a new start command (`vpn/FirewallService.kt:85–89`), successful periodic capture check (`:195–198`), and `recheck()` (`:481–483`). Both main activity and settings call `recheck()` on resume. `excludedNow()` queries `isLockdownEnabled` and `isAlwaysOn` (`:302–307`). A proxy excluded automatically by the proxy configuration is included in the VPN when lockdown becomes active, so its sockets can be relayed through NetStrazh instead of Android blocking an excluded UID. The source logic is consistent with the intended design; actual settings transitions and v2rayNG socket recovery still need runtime verification.

3. **The startup routing limitation is now explained in the app.** `ui/FirewallPage.kt:104–143` gives instructions for Always-on VPN plus Block connections without VPN. The capture probe remains a detection/recovery mechanism, not an Android-level network block. The help appropriately directs the user to Android's block for periods when the VPN is not running.

## Conditional usability issue: manually excluded proxy remains excluded under lockdown

**Priority: low, availability/configuration; no data leak demonstrated.**

`vpn/FirewallService.kt:306` suppresses only the automatically added proxy exclusion when lockdown is active; `:307` always adds `Rules.excludedPackages()`. That function includes every package manually marked with `Rules.EXCLUDE` (`data/Rules.kt:178`). Therefore a v2rayNG package manually marked “мимо Стража” remains outside the VPN under lockdown. Android's block then prevents that UID from reaching its server. The new help's unconditional claim that the proxy app stays inside (`ui/FirewallPage.kt:129–132`) does not cover this configuration.

Concrete source scenario:

1. Select v2rayNG in the proxy settings and enable the proxy.
2. Separately mark v2rayNG “мимо Стража” in its firewall rules.
3. Enable Android's Block connections without VPN.
4. `excludedNow()` still returns the v2rayNG package through `Rules.excludedPackages()`, even though its automatic proxy exclusion was removed.

Recommended behavior: warn about this combination and offer to remove the manual exclusion; alternatively explicitly define precedence of the lockdown proxy rule. Do not silently override arbitrary user exclusions without making the behavior clear. **This edge case is established by the exclusion-set construction; it has not been exercised on Android in this static audit.** It should not be described as a confirmed runtime v2rayNG failure in the final report unless separately reproduced.

## Important bounds of the implementation

- `vpn/Engine.kt`, `vpn/Nat.kt`, `vpn/NetMonitor.kt`, `data/ProxyApps.kt` are unchanged from the archived 2.0 versions. Earlier passing emulator results remain historical evidence, not new 3.5 test results.
- The first capture check begins after 200 ms and permits a 5-second probe window; periodic checks are scheduled every 60 seconds and use a 2.5-second window (`vpn/FirewallService.kt:421–427`). Those values do not establish the actual length of a routing gap. A successful probe verifies the app's own probe reached the current TUN; an independent client UID is needed for stronger regression evidence about other apps.
- The policy engine does not close the startup/stop routing window by itself. The source comment at `vpn/FirewallService.kt:111–112` (“traffic never bypasses us”) is too strong given the app's own capture/recovery code and new help. Prefer wording about establishing the replacement interface before closing the previous one, without a universal routing guarantee.
- `vpn/Engine.kt:315–322` still rejects accepted connections that do not have the expected fake-peer and TUN-local addresses plus a claimable NAT entry. This retains the 2.0 listener hardening; it is not a new 3.5 fix or proof of a previously exploitable open proxy.
- Explicit checker shutdown on service destruction remains (`vpn/FirewallService.kt:101–105`). No new missing-cleanup finding was identified in the changed lifecycle code.
- The UI deliberately prevents firewall rules for NetStrazh's own UID (`ui/AppActivity.kt:146`), and excludes its package from the app picker (`ui/PickerActivity.kt:78`). A manually created corrupt preference file is outside the normal user path reviewed here.
- Hostname resolution and Upstream socket/resource details are covered in the separate DNS audit; this review does not duplicate or infer its findings.

## Recommended runtime checks for this version

1. Fresh start notification initially checking and later protection enabled after capture verification.
2. Independent client, fresh TCP and UDP sockets before/after several off/on cycles, recording source address, process identity and current engine capture timestamp.
3. Official v2rayNG in proxy-only mode with lockdown enabled while both apps are running; assert exclusion-set transition, proxy owner identification, fresh TCP and UDP payloads, and current-engine capture. Repeat when disabling lockdown.
4. Stopped NetStrazh under lockdown must deny the independent client; removing lockdown must restore the same fixture path. This tests Android blocking, not remote-server tunnel protocols.

No new high-severity lifecycle vulnerability was established by this static review. This conclusion is bounded to the source paths above and the personal-use threat model; it does not certify all Android/OEM versions, remote proxy configurations, IPv6 networks or transition timings.
