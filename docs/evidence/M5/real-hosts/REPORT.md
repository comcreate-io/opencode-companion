# Real-host candidate 0.1.2

Observed 2026-09-28 on the owner’s USB Pixel 8 Pro, Android 16 / API 36. This is a candidate testing record, not production acceptance. No client repositories, paid provider credentials, public cloud services or release signing were used.

## Artifact and scope

- Application source: [`0af0602479ffd8fe9e7efccd62afe59a5b02d960`](https://github.com/comcreate-io/opencode-companion/commit/0af0602479ffd8fe9e7efccd62afe59a5b02d960). Subsequent test-helper and evidence changes retain this application source.
- Package/version: `dev.local.opencodecompanion.debug`, `0.1.2-dev-candidate`, code `3`, database schema `2`.
- APK SHA-256: `7cb258bb5c377ce55cca9b1f352badc70778f46b6566c2c44b10344f48c652c1`.
- Final test-helper source: `3259c0e0ecb246c8554cd0e6caff4f1f247d2129`; test APK SHA-256: `41f0bffcd36359c69d1925ae1e441527f1613109a495f8b81013b3fd782b147d`. The six core checks used the earlier test APK `a3460aba7530443f9b542fc8e7efd8b56891a421e5405972c9633996fd79e449`; their test source is unchanged by the helper corrections.
- Hosts: exact official OpenCode 1.18.32, separate disposable workspaces/passwords, private Tailscale HTTPS with normal certificate validation. The two owner acknowledgements were completed in the ordinary app.
- A local Gemma model provided real inference; the second host used the first host’s existing model service over the private network. This does not establish independent inference on the second machine. Private origins, account metadata and credentials are omitted.

## Findings and changes

The initial ordinary 0.1.1 app created a real session, displayed a local-model reply after one admitted prompt, and restored that reply after process restart without a duplicate. It also exposed two transport/layout bugs. After roughly 93 seconds of quiet durable subscriptions, three 30-second read timeouts marked a healthy host unreachable, despite healthy global heartbeats. The model list also measured to its visible text width (563px on a 1344px-wide phone), so right-side gestures missed it.

0.1.2 gives heartbeat-free durable tails no idle read deadline. Global SSE keeps its 30-second idle budget; finite requests retain 10-second read/20-second call limits. Periodic finite history replay detects a selectively stalled durable route; a per-session journal lock serializes replay with streamed cursor/record/projection updates. Failed reconciliation preserves Unavailable and cancels the sibling streams. No prompt retry or automatic resend was added. Machine, session, model and changes lists now fill the available width.

OCR review found and closed two issues before the candidate was committed: unlimited durable reads needed independent reconciliation, and the new gesture test needed to assert actual movement rather than rely on a later programmatic scroll.

A subsequent ordinary-app draft-isolation test exposed a third issue: rapid input could lose characters when the first asynchronous save acknowledged an older value. The composer now preserves local edit generations across delayed acknowledgements, clears only unchanged submitted input, and enables Send after the latest edit has saved. Draft hydration shares the save lock, preserving pending saves across host switches. The delayed-save native regression and ordinary-app rapid-input, two-host and process-recovery checks passed.

## Observed checks

| Check | Result |
|---|---|
| Formatting, 46 protocol + 71 client + 6 app JVM tests | Pass, 123 total; [build metadata](build.json) |
| Client/app lint; debug, unsigned release, instrumentation assembly | Pass; app lint zero errors and 15 existing dependency warnings |
| Sixteen native checks on the final application APK | Pass; [matrix and artifact identities](native-matrix.json), [core output](core.txt) |
| Installed APK read-back | Matches the application hash above |
| Real-host foreground idle beyond prior failure window | Final APK: Ready and restored reply observed at both ends of a 137.97-second quiet interval. Intermediate APK also passed a 158.9-second interval. Neither is continuous-state monitoring |
| Ordinary-app prompt on second real host | Final APK: native `PIXEL_CANDIDATE_OK` reply; one new admission (two total), 12 total durable events. Earlier intermediate APK: native reply plus 71 independently observed HTTPS deltas exactly matching its durable final text |
| Two-host switch and post-restart transcript recovery | Final APK: rapid input exact, separate drafts on both hosts restored after process restart, synthetic drafts cleared, authoritative histories unchanged. Actual computer reboot previously preserved both six-event histories exactly; [real-host results](real-host-checks.json) |

The six core native cases cover read/tool/draft/recreation/changes, question reply, interrupt, reading-position restoration, right-side catalog scrolling, and controlled offline/background/foreground recovery. The remaining ten checks cover the composer delayed-save/send-clear race, two-host isolation, two process-recovery stages, two large-text light/dark layout cases, two question-isolation cases, credential replacement and permission reply. The new gesture assertion verifies scroll offset increased. These fixture checks use a separate database and generated loopback HTTPS credentials; the actual-host checks run in the normally launched app over the private network.

Coordinator regressions cover durable-only stall convergence, unchanged-history idle Ready, history failure stopping siblings, concurrent history/stream projection order, and cancellation while history holds the journal lock. Transport tests exercise both silent response headers and silent bodies, retaining global timeout and cancellation behavior.

## Failed runs retained

The [initial core run](initial-core.txt) passed five existing cases but the new catalog case raced machine readiness and found New session disabled. The helper now waits for Ready while retaining its enabled assertion. A focused, strengthened gesture rerun passed. That intermediate application build is not the final artifact above.

The successful intermediate run and its hashes are retained as [intermediate core](intermediate-core.txt) and [metadata](intermediate-checks.json).

A [subsequent run](locked-core.txt) failed all six cases before reaching Compose because the phone had locked; read-back confirmed keyguard showing. It was rejected as a failed run. After the owner unlocked the phone, temporary plugged-in stay-awake was authorized, and all six cases passed on that intermediate APK. The original plugged-in stay-awake setting was restored and read back after testing. The ordinary app was left Ready on the second host, with its exact reply visible and installed APK hash verified again. Both owned temporary fixtures were stopped; real-host services remain running. App data, host credentials, accessibility services and global font scale are preserved.

The new composer regression initially failed its final empty-field assertion: Compose merged the empty editable value with the “Message” placeholder. [Failed output](composer-initial.txt) confirms the editable value was empty. The corrected assertion checks `EditableText` exactly; the missing assertion import found during compilation was fixed before rerun. A [two-host setup run](two-host-initial.txt) also raced asynchronous machine persistence; the helper now waits for the new machine identity and authenticated Ready state before selecting it. Neither helper correction changes the application APK.

The later question suite stalled during a redundant APK install before instrumentation began. [Install record](install-stall.txt) retains the failure boundary. The runner now skips installation only after matching each installed APK hash to its local artifact, bounds required installs to 90 seconds, and verifies the installed hash afterward. A device probe confirmed zero install calls for both matching artifacts; no app data was cleared. The remaining question, credential and permission suites then passed.

## Limits

The older 14-case native matrix and 25 Room/Keystore cases retain their own historical APK/source scope; the 16 current native checks are recorded separately, and the 25 platform cases were not rerun here. No fresh-export byte-for-byte rebuild is claimed for 0.1.2. Actual-host password rotation/re-pair, Wi-Fi/cellular transitions, host interruption during active execution, manual TalkBack, owner layout approval, minimum-API and landscape acceptance remain open.

History replay retains its existing 100-page budget. The per-request 20-second deadline is not a whole-replay deadline; cancellation under the journal lock is tested, but large-history latency is not established by this run.

The private development services are test-scoped. One host service is persistent; the local test host remains a foreground process. A background Android execution service, public Cloudflare deployment, per-device revocation, release distribution, iOS and hosted cloud development remain outside this candidate. Exact-head PR CI and the final OCR review are separate merge gates.
