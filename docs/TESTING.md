# Android candidate testing

This candidate connects to your OpenCode host. Start with a disposable project and follow [Host setup](HOST_SETUP.md) before allowing it to run work. It supports exactly OpenCode **1.18.32**, authenticated HTTPS and explicit shared-password acknowledgement for each machine. This is a testing handoff, not a production release.

## Identify the artifact

| Item | Candidate |
|---|---|
| Local APK | `.android-local/candidate/opencode-companion-0.1.1-candidate.apk` |
| Package | `dev.local.opencodecompanion.debug` |
| Version | `0.1.1-dev-candidate`, version code `2` |
| Android minimum | Android 9 / API 28 |
| Implementation commit | [`686367d`](https://github.com/comcreate-io/opencode-companion/commit/686367d65f4da6744f8988ea60b1bebfce5e620b) |
| APK SHA-256 | `bd2f3c0ef2229d4d15579d2d0bbfc5832003ffb4d45e20a609821343c2be24a9` |
| Debug signing certificate SHA-256 | `580fa46f785a93d6df37ead6796ae4c58cc92ca020f2419110b4e05f1285d886` |

Release signing and distribution are not established; unsigned release assembly is a build check, not an installable release.

## Install or update

With USB debugging enabled and the intended phone authorized, run from the repository root. Use its exact serial from `adb devices -l` rather than relying on whichever device happens to be selected:

```bash
adb devices -l
adb -s YOUR_DEVICE_SERIAL install -r .android-local/candidate/opencode-companion-0.1.1-candidate.apk
```

Replace `YOUR_DEVICE_SERIAL` with that device's serial. `install -r` updates a matching package/signature while retaining app data. If Android reports a signing mismatch, stop; do not uninstall just to make installation succeed. Open **OpenCode Companion** on the phone and confirm the installed package/version if another build is present.

Uninstalling or clearing app data loses local drafts and pending intent records. Do not do either while a send has an unknown outcome. This candidate uses database schema 2: an older schema-1 APK is not a safe rollback. Do not downgrade or use `adb install -d`; fix forward with a compatible build. Keep the existing data until its recovery is understood.

## Observed checks

The [accessibility regression report](evidence/M3/accessibility/REPORT.md) owns the new candidate's exact checks and limitations. Its source passed 109 JVM tests, formatting, lint (zero errors, 15 dependency-update warnings), debug/unsigned release and instrumentation assembly. A fresh source export reproduced the APK above byte for byte with the same tools and debug signing key. The new emulator checks cover reading-position restoration, answer isolation, accessible semantics, contrast and light/dark 2×-font layouts with the keyboard open. See that report for the complete final regression matrix.

**The exact 0.1.1 APK is installed on the Pixel and passed all 14 native fixture checks.** Installed hash read-back matched before and after testing. See [Pixel evidence](evidence/M3/accessibility/pixel/REPORT.md) for the suite, the initial setup-helper failure and the successful rerun. The core suite used a strengthened test helper; the application APK was unchanged. Existing app data, accessibility settings and global font scale were preserved. The ordinary app was launched after fixture cleanup.

Historical 0.1.0 observations remain in the [connected evidence report](evidence/M3/connected/REPORT.md):

- Implementation commit `10901c0` passed CI and 109 JVM tests: 46 protocol and 63 client. Debug, unsigned release and Android instrumentation APKs assembled. Lint reported no errors and 15 dependency-update warnings. A fresh source export and clean workspace build produced the identical historical APK recorded in the connected report.
- All 25 Room/Keystore platform tests passed on the isolated emulator and Carter's Pixel 8 Pro running Android 16 / API 36.
- Emulator and Pixel native runs each passed three core scenarios, one two-host scenario, two process-restart instrumentation stages, one credential-update scenario and one permission scenario. Process recovery includes durable draft and credential reuse.
- The historical 0.1.0 APK's installed checksum matched its handoff. Its eight native fixture checks passed on the unlocked Pixel over USB-forwarded HTTPS. The current 0.1.1 device result is recorded separately above; neither result establishes real-network, manual TalkBack or layout approval.

These results do not establish a named tunnel, your remote model/provider path, cellular recovery, manual TalkBack behavior or all supported Android versions. Automated semantics and bounded emulator layout checks do not establish full accessibility or Carter's phone-layout acceptance. The final artifact record must say which checks apply to its source revision.

## Test on your host and phone

1. **Connect deliberately.** Use [Host setup](HOST_SETUP.md) to verify TLS, denial without credentials and exact host version. Add the root HTTPS origin and confirm the machine/project before sending. A shared host password gives broad owner access; per-device revocation is unavailable. Provider keys stay on the host.
2. **Complete a small real session.** In a disposable repository, select your actual host-supported model, send a harmless synthetic prompt, watch incremental text/tool output, inspect changes and exercise a permission/question if the host requests one. Confirm the result on the host. Try interrupt during a bounded test run; disconnected does not mean stopped.
3. **Break the connection.** Move between Wi-Fi and cellular, briefly use airplane mode, background/reopen the app and verify that cached content is distinguishable from Ready. Preserve an unsent draft through restart. If a sent prompt becomes unknown, reconcile against the host before doing anything that could issue it twice.
4. **Check the phone layout.** Try large system font sizes, keyboard-open conversation, light/dark mode and TalkBack. Read older output during streaming and expand a tool row: the reading position and focus should stay useful. Report clipped actions, unlabeled controls or unexpected scroll jumps with synthetic content only.

Record app/source version, device/API, host version, route type, expected versus observed behavior and a minimal synthetic reproduction. Redact passwords, Authorization headers, provider keys and real repository content. A screenshot cannot establish whether the host accepted a command; include its observed state when relevant.

## Known limits and recovery

- **Credential replacement:** use **Update password** on the existing machine; it preserves drafts and journal. Unresolved sends block replacement. If the old host credential is already revoked, keep the intent blocked and inspect the host directly; this needs manual recovery. No automatic override/resend, profile deletion or restoration of revoked credentials. Ambiguous local vault state fails closed until restarting the app rechecks it. A saved replacement is not authenticated readiness.
- **Bounded transcript:** the current reducer caps retained event identities at 4,096 and cumulative wire text at 4 Mi UTF-16 code units. Large histories can reach this boundary; this is not unlimited transcript support. Report the explicit failure rather than resetting data or silently omitting content.
- **Scope:** no general file browser, slash-command composer, attachments, full terminal or iOS app in this candidate. Changes are read-only. No provisioned cloud service, browser-based Cloudflare Access integration or per-device pairing is implied.
- **Host lifecycle:** the host owns execution and must remain running. Sleep, server restart and a network fault may initially look identical to the phone. Reconnect and inspect authoritative host state; do not assume a run survived a restart.

Actual-host access and layout acceptance remain explicit decisions. Testing this artifact does not silently accept every deferred feature or remove the remote security gates in [PLAN.md](../PLAN.md).
