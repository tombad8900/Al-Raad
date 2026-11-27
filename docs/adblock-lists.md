# Ad-block lists (T6)

## Sources (the lists Brave ships upstream)

| List | URL | License |
| --- | --- | --- |
| EasyList | https://easylist.to/easylist/easylist.txt | GPLv3 / CC BY-SA 3.0 (dual) |
| EasyPrivacy | https://easylist.to/easylist/easyprivacy.txt | GPLv3 / CC BY-SA 3.0 (dual) |

Bundled baseline: `core/adblocker/src/main/assets/blocklist.txt` (see BLOCKLIST-LICENSE.txt).

## Honest scope

- Only the `||domain^` literal-domain subset of the ABP format is consumed
  (see AdblockPlusDomainParser). Cosmetic rules, exception rules, and pattern
  rules are ignored. Full ABP support is an engine decision, spike-gated.
- Updates: WorkManager roughly every 24h, plus "Update lists now" in Settings.
- Last-known-good: a download promotes only after parsing yields a sane domain
  count; a failed or malformed update keeps the previous working copy.
- Per-list toggles and per-site exceptions are persisted locally. Disabling
  "Ad blocking" stops all blocking without deleting anything.
- Anti-adblock prompts: this reduces detection surface but is NOT universal
  undetectability. Site-specific fixes belong in per-site exceptions; the
  fixture catalog for testing is Pally's docs/adblock-compat-fixtures.md.
