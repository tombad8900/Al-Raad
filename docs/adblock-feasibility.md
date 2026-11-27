# Ad-Blocking Feasibility

Assessment date: 2026-11-27
Repository snapshot reviewed: `main` at `d7dc697a45f3311252b1f5d316a7850018212b11`.
Scope: source and public documentation review only. No application code or engine dependency is changed by this document.

## Executive recommendation

Keep the existing bundled hosts blocker as the offline fallback, but do not describe it as EasyList-compatible. For a full filter engine, run a small compatibility spike with Brave's `adblock-rust` (MPL-2.0, active upstream) behind a narrow JNI adapter. It is the strongest maintained engine reviewed here and supports ABP-style network and cosmetic rules, but upstream does not provide a ready-made Android/WebView AAR or JNI adapter. Treat NDK packaging, WebView integration, EasyList/EasyPrivacy rule coverage, performance, and site compatibility as unverified until the spike passes the acceptance tests below.

The official Adblock Plus Android SDK has a ready-made `AdblockWebView` with network and element hiding, but its Android repository's latest commit is dated 2021-05-19, its build instructions depend on old JCenter/NDK/V8 tooling, and its WebView wrapper forces JavaScript on. The community `Edsuns/AdblockAndroid` is closer to the project's current WebView architecture and advertises EasyList-compatible rules and cosmetic filtering, but its latest release is v0.9.1 from 2021-07-30. Neither is a safer default than a current, pinned engine spike.

## What the current code does

These findings are from the source files on the reviewed `main` snapshot above.

- `core/adblocker/src/main/assets/blocklist.txt` is a small bundled hosts-format snapshot. The file comments say it is derived from public blocklists such as StevenBlack/hosts. `BLOCKLIST-LICENSE.txt` cites StevenBlack/hosts and MIT. It is static; the asset says there is no runtime network fetch.
- `HostsParser.parse()` in `core/adblocker/.../impl/HostsParser.kt` strips comments and parses hosts-file rows. `DomainIndex` in `core/adblocker/.../impl/DomainIndex.kt` keeps exact domains plus a reversed-label suffix trie.
- `OfflineAdBlocker` in `core/adblocker/.../impl/OfflineAdBlocker.kt` lazily loads that asset once, matches only the request host against the domain set, and skips hosts present in an in-memory concurrent whitelist. `addToWhitelist()` and `removeFromWhitelist()` are process-memory operations; no persisted per-site exception UI is wired here. Matching has no URL-path, resource-type, first-party, or EasyList rule semantics.
- `WebViewIntegrationHelper` in `core/adblocker/.../impl/WebViewIntegrationHelper.kt` installs a `WebViewClient`. Its `shouldInterceptRequest()` calls `adBlocker.shouldBlock(url)`; a match returns an empty `text/plain` HTTP 204 `WebResourceResponse`, otherwise `null` lets WebView load the resource. The code wraps the suspend check in `runBlocking(Dispatchers.IO)`, so the request callback waits for it; the first lazy asset read and parse can add latency. It does not download or update lists.
- The same helper injects `AdBlocker.cosmeticCss()` with `evaluateJavascript()` from `onPageFinished()`. `CosmeticRules.forHost()` in `core/adblocker/.../impl/CosmeticRules.kt` ignores its host argument and returns a short generic selector list. This is a CSS heuristic, not EasyList cosmetic-rule parsing.
- `BrowserScreen.kt` creates the WebView, applies `SafeWebViewConfig`, installs `PopupBlockingWebChromeClient`, and attaches the helper's client. `SafeWebViewConfig.kt` sets `setSupportMultipleWindows(true)` and `javaScriptCanOpenWindowsAutomatically = false`. `PopupBlockingWebChromeClient.onCreateWindow()` logs the attempt and returns `false`, rejecting child-window creation.
- `AppSettings.adBlockEnabled` defaults to `true`; `DataStoreSettingsRepository` persists it as `ad_block_enabled`, and `SettingsScreen` exposes an Ad blocking switch. In this snapshot, `BrowserScreen` passes `AppSettings` to `SafeWebViewConfig`, but that config only applies JavaScript, mixed-content, file-access, and related WebSettings. `WebViewIntegrationHelper` receives no ad-block setting and does not consult it. Thus the saved switch is not wired to enable/disable the current blocking path.

## WebView interception, popups, and page overlays

Android's `WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)` is a synchronous decision point: return `null` to continue the normal load, or a `WebResourceResponse` to provide the response. Android documents these important limits:

- The callback runs on a thread other than the UI thread. Do not read or mutate WebView UI state there; keep any top-level page context cached from UI-thread navigation callbacks.
- It is not called for `javascript:` or `blob:` URLs, or `file:///android_asset/` and `file:///android_res/` resources. For redirects, it is called for the initial resource URL, not each subsequent redirect URL.
- The request object supplies URL, method, headers, frame and gesture information, not a complete browser-extension-style request context or a general DOM/content-type classification. Filters depending on resource type, frame ancestry, or party relationship therefore need careful context mapping and can be less exact.
- Service-worker requests have a separate `ServiceWorkerClient.shouldInterceptRequest()` API (available from API 24). If service-worker traffic is in scope, install and test that path too; the normal WebViewClient callback alone is not proof of complete coverage.
- A request callback cannot remove an already-rendered element or reliably prevent a script from navigating the current page. It also does not make every browser network path observable or controllable.

The existing popup hook is useful but distinct from request filtering. `WebChromeClient.onCreateWindow()` is the host's new-window request hook; returning `false` rejects child-window creation, and the current `SafeWebViewConfig` explicitly enables multiple-window support so `window.open()` requests reach that hook instead of replacing the current page as a side effect of a disabled setting. This does not mean every forced-tab pattern is stopped: script-driven same-window navigation, direct top-level redirects, or links handled by another navigation path are not child-window creation. Test user-gesture and no-gesture `window.open()`, `<a target="_blank">`, and same-window redirects separately. The current hook rejects all requested windows and does not create an in-app tab.

Network rules can block known popup scripts and popup-serving hosts. Cosmetic rules can hide known overlays after they enter the DOM. Neither mechanism guarantees removal of every annoyance: an overlay may be first-party, dynamically inserted after the stylesheet is applied, use a shadow root, use changing selectors, or be essential UI. Scriptlet or procedural rules can target more cases but raise compatibility and security risk. Any such behavior needs explicit list support and device/site tests.

## Engine options reviewed

### 1. Brave `adblock-rust` — recommended spike

- Official project: https://github.com/brave/adblock-rust
- Rust crate/API: https://crates.io/crates/adblock and https://docs.rs/adblock/
- The upstream README describes a native library with network blocking, cosmetic filtering, hosts syntax, resource replacement, and uBlock Origin syntax extensions. The crate description explicitly calls out Adblock Plus syntax such as EasyList and EasyPrivacy.
- License: MPL-2.0. Review the engine license separately from the filter-list licenses before distribution.
- Maintenance: live upstream read on 2026-11-27 showed v0.13.6 and commits as recent as 2026-11-24; the crate page showed v0.13.6. This is active upstream maintenance, not a guarantee of any Android ABI/API support.
- Android/WebView fit: upstream offers Rust and JavaScript bindings and can compile native code or WASM, but the reviewed upstream does not ship a ready-made Android WebView widget, Kotlin API, or official JNI bridge. Android NDK builds and a small JNI boundary would be project work. Keep WebView interception and `WebChromeClient` integration in this app; the engine only answers match/cosmetic queries.
- Cost/constraints: no paid SDK or paid service is required by the upstream crate. Engineering work includes pinning the crate and transitive versions, building and packaging native libraries for chosen Android ABIs, native binary size, JNI lifecycle/thread rules, and ensuring filter context is supplied correctly. The repo's current Kotlin/Gradle build has no Rust toolchain or JNI integration.

### 2. Adblock Plus Android SDK / `AdblockWebView`

- Official Android binding: https://github.com/adblockplus/libadblockplus-android
- Core engine: https://github.com/adblockplus/libadblockplus
- The SDK README describes an `AdblockWebView` subclass with resource blocking and element hiding, subscription settings, and preloaded list support. It describes bundled EasyList/exception snapshots and custom subscription mapping. EasyPrivacy can be evaluated as a subscription, but its exact behavior in this app needs a compatibility test.
- License: `libadblockplus` is GPL-3.0, and the `AdblockWebView.java` source header also identifies GPL version 3. Obtain a distribution/licensing review before adopting it; do not infer that the app's existing license obligations are unchanged.
- Maintenance/build condition: the Android repository's latest commit shown by GitHub was 2021-05-19; the core repository's latest commit was 2022-05-20. The Android README still asks for Android Build Tools 30.0.2, NDK r16b, a V8 build, git submodules, and JCenter. These are substantial modern-toolchain and supply-chain risks for this Kotlin 2.x / compileSdk 35 project.
- Android/WebView fit: it provides the most direct WebView wrapper, but replacing the current Compose-created `WebView` would require retaining or reimplementing this app's WebView callbacks and client integration. Its README says AdblockWebView enables JavaScript during initialization and per-AdblockWebView enable/disable is unsupported; the project has a persisted JavaScript setting. The README also documents cookie-handler replacement and optional iframe cosmetic support with limitations. These are compatibility concerns, not merely wiring details.
- Cost/constraints: no software fee is stated in the repository. Native V8/NDK builds, binary size, outdated repositories, GPL-3.0 obligations, and migration of the current WebView/client behavior are material costs.

### 3. `Edsuns/AdblockAndroid` — community Android library, not preferred

- Project and source: https://github.com/Edsuns/AdblockAndroid
- Release page: https://github.com/Edsuns/AdblockAndroid/releases
- Its README describes Kotlin, C++, JavaScript, and JNI integration with WebView, EasyList-compatible filters, element hiding, extended CSS, scriptlets, background list download, and custom rules. It demonstrates `shouldInterceptRequest()` plus `performScript()` on page start.
- License: LGPL-2.1, as identified by the project repository.
- Maintenance: GitHub's live page showed latest release v0.9.1 on 2021-07-30 and latest commit on 2021-08-19. Treat it as dormant unless a fresh maintenance check or maintained fork proves otherwise.
- Android/WebView fit: closer to the current callback-based WebView integration than replacing the whole view, but it embeds native code and its recent Android/WebView/API compatibility is not verified. README samples use JitPack and dated filter setup. Do not add a JitPack artifact to production without pinning and verifying reproducible artifacts, ABI coverage, minSdk 24, current AGP/NDK, and license obligations.
- Cost/constraints: no paid SDK fee is stated; native build/packaging and updating the older fork are the main costs. The latest published release age makes this a weaker production base than actively maintained upstream code.

## Filter lists and licensing

- EasyList: https://easylist.to/easylist/easylist.txt
- EasyPrivacy: https://easylist.to/easylist/easyprivacy.txt
- List project and maintenance: https://github.com/easylist/easylist and https://easylist.to/
- License terms: https://easylist.to/pages/licence.html

Fresh list reads on 2026-11-27 showed EasyList version `202611271849` and EasyPrivacy version `202611271853`; each declared an update interval of four days. The repository showed same-day updates to both list directories. EasyList says its contents are dual-licensed under GPL-3.0-or-later or CC BY-SA 3.0-or-later, with attribution to the EasyList authors when required. The official license page warns that externally hosted lists can have other terms. Bundle only approved lists, record source URL/version/date/license/attribution, and check each additional list independently. A filter engine's code license does not replace the list's license.

## Offline-first integration proposal

1. Keep a small, reviewed baseline snapshot in app assets for first install and airplane-mode recovery. Parse it off the UI thread and warm the engine before normal page loads. The request callback should only consult an immutable, already-parsed in-memory snapshot; never download or parse a full list in `shouldInterceptRequest()`.
2. Store active and previous known-good list snapshots and metadata locally. An update worker may fetch only configured HTTPS list URLs when a network is available; use bounded timeouts and size limits, conditional requests where supported, and record the fetched list's version, digest, and timestamp. Browsing and matching remain local when offline.
3. Write downloads to a temporary file. Validate the list header/format, size, digest, parse success, and a small set of canary rules before promoting it. Build the candidate engine/index off-thread. Promote the new list and matching engine snapshot atomically only after validation succeeds. On any HTTP, parse, validation, storage, or crash failure, keep the active and previous snapshots untouched and continue using the last known-good snapshot (then the bundled baseline if no good update exists). Never replace a working list with an empty or partial download.
4. Implement user-controlled exceptions as persisted site settings, separate from the filter files. Normalize the host consistently (case, trailing dot, IDN/punycode) and offer an explicit exact-host versus include-subdomains scope. Keep network-block exceptions and cosmetic-filter disabling separately controllable. Make the exception lookup thread-safe and apply it to the top-level site context, not merely a request host.
5. Wire `AppSettings.adBlockEnabled` to both network and cosmetic decisions. Turning it off must bypass both consistently; turning it back on must use the existing active snapshot. Expose list status, last successful update, and per-site exceptions in Settings. Do not make list updates a prerequisite for browsing.
6. For cosmetic CSS, install validated selectors early enough to handle the initial DOM, then optionally reapply to later DOM changes using a narrowly scoped observer. Respect JavaScript being disabled, avoid injecting unsafe rule text, and measure work per navigation. If JS is disabled, network blocking should still work and the UI should say cosmetic filtering is unavailable rather than failing the page.

## Recommendation and acceptance tests

Proceed with a contained `adblock-rust` JNI proof of concept while keeping the current hosts matcher as a fallback. Preserve the existing `AdBlocker` boundary where feasible, but add the request context the engine needs. Do not promise whole-browser coverage or merge the candidate until all of these tests pass on a pinned engine and exact Android build:

1. Parse fixtures containing EasyList network rules, EasyPrivacy rules, allow/exception rules, domain exclusions, resource types, and cosmetic rules. Assert block/allow outcomes against known expected URLs and page origins; include malformed and unsupported-rule behavior.
2. On API 24 and a current API/WebView, verify `shouldInterceptRequest()` returns a block response only for matched requests and `null` for allowed ones; verify the callback never touches WebView state from its worker thread and matching does not wait on network or list parsing. Test initial URL redirects, `javascript:`, `blob:`, and local asset exclusions as documented.
3. If supported service-worker traffic is in scope, verify the API 24 `ServiceWorkerClient` path separately. Test requests with missing `Referer`, subframes, and different top-level sites so first-party/third-party context is not guessed silently.
4. Verify persisted `adBlockEnabled=false` disables network and cosmetic filtering, and `true` enables both supported paths. Verify exact-host and include-subdomains exceptions independently, including persistence across process restart and thread-safe toggles.
5. In airplane mode, cold-start and reopen the app, then confirm the bundled or last-good snapshot still blocks a known fixture. Simulate a truncated response, wrong format, parser failure, disk-write failure, and process interruption during update; after each, confirm the prior snapshot remains active and the app continues to browse.
6. Verify cosmetic selectors on a deterministic local page after initial render and after delayed DOM insertion; test JS disabled, missing `document.head`, frames, and common-site layouts. Confirm false-positive selectors do not hide ordinary controls or content.
7. Verify `window.open()` with and without a user gesture and `<a target="_blank">` never create unwanted tabs; separately verify same-window navigation remains a distinct policy path. Test a harmless test overlay and a controlled anti-adblock fixture, and record partial success or breakage rather than asserting universal success.
8. Run a small representative page suite on API 24 and a current Android System WebView, compare rendering and navigation against blocking off, and inspect console/network failures. Keep the allowed-site list and rule-engine version fixed so regressions are reproducible.

## Verified versus still unverified

Verified from source in `main@d7dc697a45f3311252b1f5d316a7850018212b11`: current hosts-only asset/parser/index, WebView callback and 204 blocking behavior, generic CSS injection, popup hook, settings persistence and the missing `adBlockEnabled` connection, Android `minSdk 24`, current public list metadata/license pages, and the upstream project/release metadata cited above.

Not verified by this documentation pass: building or benchmarking any third-party engine in this repository; JNI/NDK compatibility on the project's supported ABIs; actual EasyList/EasyPrivacy coverage under Android WebView; dynamic DOM/frame/service-worker behavior on representative sites; false-positive rates, anti-adblock detection outcomes, battery/memory costs, binary size, or absence of site breakage. These require the acceptance spike and exact-build device/emulator tests.

## Sources

- Project instructions: https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/docs/AGENTS.md
- Current blocker: https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/core/adblocker/src/main/kotlin/com/alraad/core/adblocker/impl/OfflineAdBlocker.kt
- WebView hook: https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/core/adblocker/src/main/kotlin/com/alraad/core/adblocker/impl/WebViewIntegrationHelper.kt
- Cosmetic selectors: https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/core/adblocker/src/main/kotlin/com/alraad/core/adblocker/impl/CosmeticRules.kt
- Browser/settings integration: https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/feature/browser/src/main/kotlin/com/alraad/feature/browser/ui/BrowserScreen.kt, https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/feature/browser/src/main/kotlin/com/alraad/feature/browser/web/SafeWebViewConfig.kt, https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/feature/browser/src/main/kotlin/com/alraad/feature/browser/web/PopupBlockingWebChromeClient.kt, https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/feature/library/src/main/kotlin/com/alraad/feature/library/settings/SettingsScreen.kt, and https://github.com/tombad8900/Al-Raad/blob/d7dc697a45f3311252b1f5d316a7850018212b11/data/src/main/kotlin/com/alraad/data/repo/DataStoreSettingsRepository.kt
- Android interception API: https://developer.android.com/reference/android/webkit/WebViewClient
- Android popup API: https://developer.android.com/reference/android/webkit/WebChromeClient and https://developer.android.com/reference/android/webkit/WebSettings#setSupportMultipleWindows(boolean)
- Android service-worker API: https://developer.android.com/reference/android/webkit/ServiceWorkerClient
- Brave engine and license: https://github.com/brave/adblock-rust and https://crates.io/crates/adblock
- Adblock Plus Android and core: https://github.com/adblockplus/libadblockplus-android and https://github.com/adblockplus/libadblockplus
- Edsuns Android engine/releases: https://github.com/Edsuns/AdblockAndroid and https://github.com/Edsuns/AdblockAndroid/releases
- EasyList / EasyPrivacy / list license: https://easylist.to/easylist/easylist.txt, https://easylist.to/easylist/easyprivacy.txt, https://github.com/easylist/easylist, and https://easylist.to/pages/licence.html
