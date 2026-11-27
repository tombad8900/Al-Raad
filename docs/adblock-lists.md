# T6 Ad-Block List Sources and Compatibility

Checked against upstream sources on 2026-11-27. This is a source inventory and compatibility note; it does not claim that remote list updates, per-list controls, or runtime compatibility are implemented.

## Current Al-Raad implementation on `main`

- `core/adblocker/src/main/kotlin/com/alraad/core/adblocker/impl/HostsParser.kt` parses hosts-file entries and comments. It is not an EasyList/ABP parser and does not interpret `||` network rules, `$` options, `@@` exceptions, or `##` cosmetic rules.
- `OfflineAdBlocker.kt` loads the bundled `core/adblocker/src/main/assets/blocklist.txt`; its class comment and `BLOCKLIST-LICENSE.txt` describe a static offline snapshot with no runtime downloads. Its whitelist is in memory in this class.
- `DataStoreSettingsRepository.kt` persists `AppSettings.adBlockEnabled`. The inspected `OfflineAdBlocker` does not read that setting: `shouldBlock` uses the loaded index, and `cosmeticCss` returns `CosmeticRules.forHost(host)`. The current settings screen exposes the overall ad-block switch, not per-list controls.
- These observations are source reads of `main`, not build, emulator, or browser-test results.

## Host-format candidate for the existing parser

| List | Exact source URL / observed version | Format | License and attribution | Status |
| --- | --- | --- | --- | --- |
| StevenBlack/hosts | Pinned source: `https://raw.githubusercontent.com/StevenBlack/hosts/1cdc93bfa0c5c939956ce0440f99cef4c4c40e95/hosts`. The file header reports `Date: 24 November 2026 03:28:58 (UTC)` and `81,676` unique domains; the observed upstream `master` commit was `1cdc93bfa0c5c939956ce0440f99cef4c4c40e95`. | Hosts file | The repository's existing `core/adblocker/src/main/assets/BLOCKLIST-LICENSE.txt` attributes the bundled baseline to sources such as StevenBlack/hosts and states MIT. Preserve that attribution and MIT notice; verify upstream terms again before shipping a separately updated copy. | Syntactically compatible with `HostsParser`; not yet configured as a runtime subscription. |

## Brave source catalog: reference only until an ABP-capable engine is available

Brave's README says it uses community-maintained EasyList, EasyPrivacy, and uBlock Origin lists by default; the Brave catalog declares sources with a format such as `Standard` or `Hosts`. The catalog and list files below were read at the observed upstream revisions. The current Al-Raad `HostsParser` cannot consume the `Standard` entries as intended, so do not enable these URLs as active subscriptions through that parser.

| List | Exact URL pinned to observed commit | Observed format/version | License and attribution | Compatibility |
| --- | --- | --- | --- | --- |
| Brave Specific | `https://raw.githubusercontent.com/brave/adblock-lists/58d312984b89c3b43bf92b48004066c3d50da883/brave-lists/brave-specific.txt` | `Standard`; repository snapshot commit `58d312984b89c3b43bf92b48004066c3d50da883`; file header says `Expires: 2 days`. | `brave/adblock-lists` repository license is MPL-2.0. Retain the project attribution and license notice. | Not compatible with `HostsParser`; includes ABP/uBO network, exception, and cosmetic syntax. |
| Brave Android-Specific Rules | `https://raw.githubusercontent.com/brave/adblock-lists/58d312984b89c3b43bf92b48004066c3d50da883/brave-lists/brave-android-specific.txt` | `Standard`; same pinned repository commit; file header says `Expires: 2 days`. | MPL-2.0 for the Brave repository file; retain attribution and license notice. | Not compatible with `HostsParser`; observed file contains cosmetic selectors. |
| Brave Social | `https://raw.githubusercontent.com/brave/adblock-lists/58d312984b89c3b43bf92b48004066c3d50da883/brave-lists/brave-social.txt` | `Standard`; same pinned repository commit. | MPL-2.0 for the Brave repository file; retain attribution and license notice. | Not compatible with `HostsParser`. |
| Brave Unbreak | `https://raw.githubusercontent.com/brave/adblock-lists/58d312984b89c3b43bf92b48004066c3d50da883/brave-lists/brave-unbreak.txt` | `Standard`; same pinned repository commit. | MPL-2.0 for the Brave repository file; retain attribution and license notice. | Not compatible with `HostsParser`. |
| EasyList | `https://easylist.to/easylist/easylist.txt` | Header observed version `202611272036`; upstream commit `0bf6c5762e7156ea5ea24436ef4c12b18477d699`; last modified 2026-11-27 20:36 UTC. | EasyList's official license page says EasyList repository contents are dual-licensed under GPL-3.0-or-later or CC BY-SA 3.0-or-later unless otherwise noted. Attribute to “The EasyList authors (https://easylist.to/)” where required. | Brave catalog format is `Standard`; not compatible with `HostsParser`. |
| EasyPrivacy | `https://easylist.to/easylist/easyprivacy.txt` | Header observed version `202611272036`; upstream commit `0bf6c5762e7156ea5ea24436ef4c12b18477d699`; last modified 2026-11-27 20:36 UTC. | Same EasyList repository licensing and attribution above; re-check any list-specific notice before redistribution. | Brave catalog format is `Standard`; not compatible with `HostsParser`. |

The current Brave source catalog was observed at `https://raw.githubusercontent.com/brave/adblock-resources/c5d44edb831e78260cc1c2c1ca71bfd30f195857/filter_lists/list_catalog.json`; the observed catalog commit is `c5d44edb831e78260cc1c2c1ca71bfd30f195857`. Brave's list-source README is `https://raw.githubusercontent.com/brave/adblock-lists/58d312984b89c3b43bf92b48004066c3d50da883/README.md`. The Brave repository's MPL-2.0 license is not a blanket license for third-party sources referenced by that catalog; check each upstream source's terms and attribution separately.

## Version and update handling required by T6

- Prefer immutable commit-pinned URLs where the upstream publishes them. For rolling subscription URLs, record the source URL, upstream version/header, `ETag` and/or `Last-Modified` when available, fetched time, and a content SHA-256. Do not treat a moving `master` URL as a pinned version.
- Download only over HTTPS with bounded timeouts and byte limits. Parse and validate away from `shouldInterceptRequest`; validate the expected format, recognizable header, non-empty meaningful rules, canary entries, and sensible count/size bounds before promotion.
- Keep the last-known-good snapshot active when a request, response, or validation fails. Promote a complete validated snapshot atomically and retain a bundled baseline for first-run/offline recovery.
- Persist each list's enable state and per-site exception mode separately. Do not describe exact-host and include-subdomains exceptions as equivalent.
- Use periodic WorkManager refresh, not constant polling. No download, parsing, refresh worker, settings persistence, or promotion behavior is claimed by this document.

## Compatibility and acceptance evidence

`docs/adblock-compat-fixtures.md` on `pally/adblock-compat-fixtures` records 10 candidate sites, evidence limits, and a manual comparison protocol. The fixture catalog is not evidence of an Al-Raad WebView run. The following still require executable tests on an integrated build: (1) a known ad/tracker request is blocked while the page and video continue working; (2) anti-adblock behavior is recorded with blocking on and off and any exception is narrow; (3) ordinary controls/content remain visible with cosmetic rules; (4) global OFF disables both request blocking and cosmetic CSS, while ON restores both; (5) failed, truncated, malformed, or partial downloads leave the previous snapshot active.

Do not claim complete blocking, universal compatibility, or undetectable blocking. The full EasyList-syntax engine remains a separate feasibility decision; this list manager alone does not provide Brave-level parity.

## Sources

- Brave list source overview: `https://github.com/brave/adblock-lists`
- Brave list catalog: `https://github.com/brave/adblock-resources/blob/master/filter_lists/list_catalog.json`
- Brave repository license: `https://github.com/brave/adblock-lists/blob/master/LICENSE`
- EasyList license terms: `https://easylist.to/pages/licence.html`
- EasyList project overview: `https://easylist.to/`
- Existing bundled-list attribution: `core/adblocker/src/main/assets/BLOCKLIST-LICENSE.txt`
