package com.alraad.core.adblocker.impl

/** How a list is written. Only [HOSTS] lists are fully expressible by the domain engine. */
enum class BlocklistFormat { HOSTS, ABP }

/** One downloadable blocklist: identity, source, license and on/off state key. */
data class BlocklistSource(
    val id: String,
    val name: String,
    val url: String,
    val license: String,
    val enabledByDefault: Boolean,
    /** [BlocklistFormat.ABP] lists need a real rule matcher; the domain engine keeps them inactive. */
    val format: BlocklistFormat = BlocklistFormat.ABP,
    /** A download below this many parsed domains never promotes (last-known-good kept). */
    val minDomains: Int = 100,
)

/**
 * Brave's official lists: the upstream EasyList family Brave builds on, plus
 * Brave's own lists from brave/adblock-lists (MPL-2.0), and the bundled asset as
 * the always-available baseline. Only [BlocklistFormat.HOSTS] lists are active today.
 * ABP-syntax lists stay in the catalog with their toggles but are inactive until a matcher
 * that honors path, option, exception and cosmetic rules exists; a domain-only parser cannot
 * represent them faithfully (it widened path rules into whole-site blocks).
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
                format = BlocklistFormat.HOSTS,
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
                minDomains = 10,
            ),
            // Brave Unbreak is deliberately excluded: it is almost entirely @@
            // exception rules, which this domain-subset engine never applies, so a
            // toggle for it would promise protection it cannot deliver.
        )

    /** True when the list contributes blocks today. */
    fun isActiveFormat(source: BlocklistSource): Boolean = source.format == BlocklistFormat.HOSTS

    fun byId(id: String): BlocklistSource? = sources.firstOrNull { it.id == id }
}
