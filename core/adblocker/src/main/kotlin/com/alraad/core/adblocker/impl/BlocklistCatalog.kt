package com.alraad.core.adblocker.impl

/** One downloadable blocklist: identity, source, license and on/off state key. */
data class BlocklistSource(
    val id: String,
    val name: String,
    val url: String,
    val license: String,
    val enabledByDefault: Boolean,
    /** A download below this many parsed domains never promotes (last-known-good kept). */
    val minDomains: Int = 100,
    /** True when the list is plain hosts format (trusted domain-for-domain), false for Adblock Plus syntax. */
    val hostsFormat: Boolean = false,
)

/**
 * Brave's official lists: the upstream EasyList family Brave builds on, plus
 * Brave's own lists from brave/adblock-lists (MPL-2.0), and the bundled asset as
 * the always-available baseline. Only the `||domain^` subset is consumed today;
 * Brave lists carry many @@ / cosmetic / scriptlet rules this engine ignores.
 * See docs/adblock-lists.md for exact scope and licenses.
 */
object BlocklistCatalog {
    const val BUNDLED_ID = "bundled-hosts"

    val sources =
        listOf(
            BlocklistSource(
                id = "stevenblack-hosts",
                name = "StevenBlack hosts",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                license = "MIT",
                enabledByDefault = true,
                hostsFormat = true,
            ),
            BlocklistSource(
                id = "easylist",
                name = "EasyList",
                url = "https://easylist.to/easylist/easylist.txt",
                license = "GPLv3 / CC BY-SA 3.0 (dual)",
                enabledByDefault = true,
            ),
            BlocklistSource(
                id = "easyprivacy",
                name = "EasyPrivacy",
                url = "https://easylist.to/easylist/easyprivacy.txt",
                license = "GPLv3 / CC BY-SA 3.0 (dual)",
                enabledByDefault = true,
            ),
            BlocklistSource(
                id = "brave-specific",
                name = "Brave Specific",
                url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt",
                license = "MPL-2.0",
                enabledByDefault = true,
                minDomains = 10,
            ),
            BlocklistSource(
                id = "brave-android-specific",
                name = "Brave Android Specific",
                url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-android-specific.txt",
                license = "MPL-2.0",
                enabledByDefault = true,
                minDomains = 10,
            ),
            BlocklistSource(
                id = "brave-firstparty",
                name = "Brave Firstparty",
                url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt",
                license = "MPL-2.0",
                enabledByDefault = true,
                minDomains = 10,
            ),
            BlocklistSource(
                id = "brave-cookie-specific",
                name = "Brave Cookie Notices",
                url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-cookie-specific.txt",
                license = "MPL-2.0",
                enabledByDefault = true,
                minDomains = 10,
            ),
            BlocklistSource(
                id = "brave-social",
                name = "Brave Social",
                url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt",
                license = "MPL-2.0",
                enabledByDefault = true,
                minDomains = 3,
            ),
            // Brave Unbreak is deliberately excluded: it is almost entirely @@
            // exception rules, which this domain-subset engine never applies, so a
            // toggle for it would promise protection it cannot deliver.
        )

    fun byId(id: String): BlocklistSource? = sources.firstOrNull { it.id == id }
}
