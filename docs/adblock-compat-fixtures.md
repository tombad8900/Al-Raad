# Al-Raad Ad-block Compatibility Fixture Catalog

As of 2026-11-27. This is a target-selection and source-evidence catalog for manual compatibility tests, not a report of Al-Raad app results.

## Scope and evidence limits

The fixture list covers active streaming pages and Arabic-language video/news pages. The public pages and cited material below were read on 2026-11-27. A public-page read is not an Al-Raad WebView test. It does not prove that the video played, that an ad-block prompt would appear with another browser or region, or that a particular request domain caused a failure.

No Al-Raad APK, app WebView, controlled block-off/block-on comparison, video playback, or request-level filter run was executed for this catalog. Therefore every Al-Raad playback result is marked **Not tested**. A missing prompt in extracted page text means only that none appeared in that read; it is not evidence that the site never shows one. France 24's page did display a player-block message in the public-page read, but its cause was not isolated.

Use these labels:
- **Documented/report:** a platform or ad-block vendor describes the behavior; this is not a result from Al-Raad.
- **Page-read observation:** text from a current public page fetched on the date above; it may depend on consent, region, account, or the reader environment.
- **Not tested:** no controlled Al-Raad comparison was run.

## Candidate fixtures

### 1. YouTube
- Target: https://www.youtube.com/watch?v=bNyUyrR0PHo
- Anti-adblock signal: **Documented.** Google's current help page says it asks users to allow ads or subscribe to Premium and may block playback if ad blocking continues. AdBlock's support article describes the warning as intermittent. This establishes a known compatibility risk, not a prompt observed in Al-Raad. [1][2]
- Video with domain blocking: **Not tested in Al-Raad.** Google's page confirms playback restriction is possible when ad blockers are used. [1]
- Exception: no automatic exception. If the user wants uninterrupted playback and an on/off test confirms the warning or playback failure is caused by filtering, offer an explicit exact-host exception for `www.youtube.com`; do not enable it by default.

### 2. Twitch
- Target: https://www.twitch.tv/directory/all
- Anti-adblock signal: **Documented/report.** AdGuard's 2026 article says Twitch may show a purple ad-break/interruption screen in some cases. The article also explains that some ads are inserted into the live stream itself, so request blocking can interrupt content as well as fail to remove the ad. This is vendor guidance, not a controlled Al-Raad result. [3]
- Video with domain blocking: **Not tested in Al-Raad.** Treat live playback as a high-risk fixture because the cited guide describes same-stream ad insertion and possible interruptions. [3]
- Exception: none by default. If baseline playback works and filtering alone breaks the stream, the user may allow the exact host `www.twitch.tv` for that site and rerun the comparison.

### 3. MBC Shahid
- Target: https://shahid.mbc.net/en/livestream/Al-Arabiya/livechannel-387286
- Anti-adblock signal: **Not confirmed in sources reviewed.** The public page read showed a Live schedule and a Free Preview label; no anti-adblock text appeared in the extracted page. [4]
- Video with domain blocking: **Not tested in Al-Raad.** The page read did not establish that playback started.
- Exception: none by default. If a controlled test isolates a filter-caused failure, consider only the exact host `shahid.mbc.net`. Keep account, entitlement, regional availability, and service errors separate from ad-block results.

### 4. STARZPLAY Live
- Target: https://starzplay.com/en/live
- Anti-adblock signal: **Not confirmed in sources reviewed.** STARZPLAY's own page describes free, ad-supported viewing and live channels; its Live page was readable, but no anti-adblock behavior was established. [5][6]
- Video with domain blocking: **Not tested in Al-Raad.**
- Exception: none by default. If the same stream works with filtering off and fails with it on, test an explicit exact-host exception for `starzplay.com` only.

### 5. OSN+
- Target: https://osnplus.com/en-sa
- Anti-adblock signal: **Not confirmed in sources reviewed.** The current page advertises streaming and an ad-free subscription; the page also presents sign-in/region selection. [7]
- Video with domain blocking: **Not tested.** The public read did not establish access to a playable title. Only test content available to a properly authorized account; do not treat sign-in, subscription, DRM, or regional restrictions as ad-block failures.
- Exception: none by default. If a permitted test later isolates a filter-only failure, consider the exact host `osnplus.com`.

### 6. Roya TV Live
- Target: https://en.roya.tv/live-stream
- Anti-adblock signal: **Not confirmed in sources reviewed.** A current search result for Roya's own page showed its Live Stream page and player shell. A direct page fetch failed with a DNS lookup error in this research pass, so the page itself was not independently read. [8]
- Video with domain blocking: **Not tested.** The search result's player shell is not playback evidence.
- Exception: none by default. If a controlled Al-Raad comparison reproduces a filter-only failure, consider the exact host `en.roya.tv`.

### 7. Al Jazeera Arabic Live
- Target: https://www.aljazeera.net/video/live
- Anti-adblock signal: **No prompt in the fetched page text; overall behavior unknown.** The current page read showed a live-player section and broadcast schedule. It did not establish that video was playing or that blocking was enabled. [9]
- Video with domain blocking: **Not tested in Al-Raad.**
- Exception: none by default. If a controlled test proves filtering alone breaks playback, consider the exact host `www.aljazeera.net`.

### 8. Al Arabiya Live
- Target: https://english.alarabiya.net/live-stream
- Anti-adblock signal: **No prompt in the fetched page text; overall behavior unknown.** The current page read showed a player loading state and a cookie-consent notice. No ad-block state was controlled. [10]
- Video with domain blocking: **Not tested in Al-Raad.** The player loading state is not proof of either success or a filter-caused failure.
- Exception: none by default. If an on/off test isolates the filter as the cause, consider the exact host `english.alarabiya.net`.

### 9. France 24 Arabic Live
- Target: https://www.france24.com/ar/live
- Anti-adblock signal: **Page-read observation, cause unconfirmed.** The current fetched page displayed a message that an application in the browser was preventing the video player from loading and asked the viewer to disable it. The same page said YouTube viewing-metrics and advertising consent was needed to show the embedded video. This is a useful compatibility fixture, but it does not prove the Al-Raad blocker caused the message. [11]
- Video with domain blocking: **Not tested in Al-Raad.** The page read did not produce playable video.
- Exception: do not add a default exception. In testing, keep YouTube consent choice identical in both runs; compare filtering off/on first. If only filtering-on fails, test an explicit exact-host exception for `www.france24.com`. Do not broaden it to YouTube or unrelated hosts without separate evidence.

### 10. BBC News Arabic
- Target: https://www.bbc.com/arabic
- Anti-adblock signal: **No prompt in the current page read; a historical compatibility note exists.** The current Arabic homepage showed video items and a live-TV link. A BBC help article published in 2016 says ad-blocking software could affect its then-current embedded player and suggested allowing `bbc.co.uk`; that article is Flash-era guidance and is not evidence about today's player. [12][13]
- Video with domain blocking: **Not tested in Al-Raad.**
- Exception: none by default. If a current Al-Raad test proves a filter-only failure, consider only the exact host `www.bbc.com`; do not apply the old `bbc.co.uk` suggestion automatically.

## Manual comparison protocol

For each target that can be tested legally without new credentials:
1. Record the exact page URL, date/time, device/WebView build, region, list revision, and settings. Keep the same content, consent choice, network, and account state across runs.
2. With ad blocking off, load the page, start playback if available, and verify the visible player controls. Record whether playback starts and whether play/pause/seek controls respond.
3. Repeat with the assigned blocklists enabled and no site exception. Record exact prompt text, visible content, playback state, and the host of any failed request only if the test log provides it.
4. If the on/off comparison isolates a failure to filtering, add a user-selected **exact-host** exception, repeat, and record whether playback returns. Do not use a broad wildcard or claim the exception fixed the issue until the retry confirms it.
5. Mark login-required, subscription-only, region-restricted, DRM-protected, unavailable, or transiently failing cases as **Not tested** or **Inconclusive**, with the reason. Do not bypass those controls.

The acceptance record should report partial success and any breakage per fixture. It should not claim universal ad blocking, undetectability, or that all sites work without exceptions. A prompt absent from one run is not a guarantee about later sessions or regions.

## Sources

1. Google, Allow ads on videos that you watch: https://support.google.com/youtube/answer/14129599
2. AdBlock Help Center, What to do if you are seeing YouTube's anti-adblocking warning: https://helpcenter.getadblock.com/adblock-help-center/what-to-do-if-you-are-seeing-youtube-s-anti-adblocking-warning
3. AdGuard, How to block ads on Twitch: https://adguard.com/en/article/how-to-block-twitch-ads.html
4. MBC Shahid, Al Arabiya Live: https://shahid.mbc.net/en/livestream/Al-Arabiya/livechannel-387286
5. STARZPLAY Live: https://starzplay.com/en/live
6. STARZPLAY About Us: https://starzplay.com/en/aboutus
7. OSN+: https://osnplus.com/en-sa
8. Roya TV Live Stream: https://en.roya.tv/live-stream
9. Al Jazeera Arabic Live: https://www.aljazeera.net/video/live
10. Al Arabiya English Livestream: https://english.alarabiya.net/live-stream
11. France 24 Arabic Live: https://www.france24.com/ar/live
12. BBC News Arabic: https://www.bbc.com/arabic
13. BBC, Your guide to the BBC Embedded Media Player, published 2016-06-16: https://www.bbc.com/news/help-36551036
