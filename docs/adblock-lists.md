# Ad-block lists (T6)

Incorporates Pally's verified source inventory (branch pally/adblock-lists-manager, commit 1034d2a): upstream URLs, observed versions, and license terms he checked on 2026-11-27.

## Sources (Brave's official list set: upstream lists plus Brave's own)

| List | URL | License |
| --- | --- | --- |
| EasyList | https://easylist.to/easylist/easylist.txt | GPL-3.0-or-later / CC BY-SA 3.0-or-later (dual) — attribute "The EasyList authors (https://easylist.to/)" |
| EasyPrivacy | https://easylist.to/easylist/easyprivacy.txt | same dual license |
| StevenBlack/hosts | https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts | MIT |
| Brave Specific | https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt | MPL-2.0 |
| Brave Android Specific | https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-android-specific.txt | MPL-2.0 |
| Brave Firstparty | https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt | MPL-2.0 |
| Brave Cookie Notices | https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-cookie-specific.txt | MPL-2.0 |
| Brave Social | https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt | MPL-2.0 |

Bundled baseline: `core/adblocker/src/main/assets/blocklist.txt` (see BLOCKLIST-LICENSE.txt).

## Honest scope

- `HostsParser` alone cannot consume ABP "Standard" lists (Pally's finding, correct).
  That is why downloads go through `AdblockPlusDomainParser`: it extracts only the
  `||domain^` literal-domain subset. Cosmetic rules, `@@` exceptions, and pattern
  rules are ignored. Full ABP support is an engine decision, spike-gated.
- Brave's own lists (specific, android-specific, firstparty, cookie-notices,
  social, unbreak; MPL-2.0) ARE in the catalog. Effective coverage caveat: they
  carry many `@@` exception, cosmetic, and scriptlet rules, and this engine
  applies only the literal-domain subset, so their effective domain coverage is
  smaller than Brave's own. Brave Unbreak is deliberately excluded from the
  catalog: it is almost entirely `@@` exceptions this engine cannot apply, so a
  toggle for it would promise protection it cannot deliver. Extending the engine
  (cosmetic filtering, scriptlets, `@@` exceptions) is unfinished, spike-gated
  work.
- Updates: WorkManager roughly every 24h, plus "Update lists now" in Settings.
- Last-known-good: a download promotes only after parsing yields a sane domain
  count; a failed or malformed update keeps the previous working copy.
- Per-list toggles and per-site exceptions are persisted locally. Disabling
  "Ad blocking" stops all blocking without deleting anything.
- Anti-adblock prompts: this reduces detection surface but is NOT universal
  undetectability. Site-specific fixes belong in per-site exceptions; the fixture
  catalog for testing is Pally's docs/adblock-compat-fixtures.md.
