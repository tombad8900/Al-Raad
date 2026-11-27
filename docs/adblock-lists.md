# Ad-block lists (T6)

Incorporates Pally's verified source inventory (branch pally/adblock-lists-manager, commit 1034d2a): upstream URLs, observed versions, and license terms he checked on 2026-11-27.

## Sources (the lists Brave ships upstream, plus a hosts-format list)

| List | URL | License |
| --- | --- | --- |
| EasyList | https://easylist.to/easylist/easylist.txt | GPL-3.0-or-later / CC BY-SA 3.0-or-later (dual) — attribute "The EasyList authors (https://easylist.to/)" |
| EasyPrivacy | https://easylist.to/easylist/easyprivacy.txt | same dual license |
| StevenBlack/hosts | https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts | MIT |

Bundled baseline: `core/adblocker/src/main/assets/blocklist.txt` (see BLOCKLIST-LICENSE.txt).

## Honest scope

- `HostsParser` alone cannot consume ABP "Standard" lists (Pally's finding, correct).
  That is why downloads go through `AdblockPlusDomainParser`: it extracts only the
  `||domain^` literal-domain subset. Cosmetic rules, `@@` exceptions, and pattern
  rules are ignored. Full ABP support is an engine decision, spike-gated.
- Brave-specific lists (brave-specific, android-specific, social, unbreak) are
  MPL-2.0 and mostly non-domain rules; they stay out of the active catalog until
  the engine supports more than the domain subset.
- Updates: WorkManager roughly every 24h, plus "Update lists now" in Settings.
- Last-known-good: a download promotes only after parsing yields a sane domain
  count; a failed or malformed update keeps the previous working copy.
- Per-list toggles and per-site exceptions are persisted locally. Disabling
  "Ad blocking" stops all blocking without deleting anything.
- Anti-adblock prompts: this reduces detection surface but is NOT universal
  undetectability. Site-specific fixes belong in per-site exceptions; the fixture
  catalog for testing is Pally's docs/adblock-compat-fixtures.md.
