package com.alraad.core.adblocker.impl

/** One downloadable blocklist: identity, source, license and on/off state key. */
data class BlocklistSource(
    val id: String,
    val name: String,
    val url: String,
    val license: String,
    val enabledByDefault: Boolean,
)

/**
 * The upstream lists Brave ships (EasyList family), plus the bundled asset as the
 * always-available baseline. Only the `||domain^` subset is consumed today; see
 * docs/adblock-lists.md for exact scope and licenses.
 */
object BlocklistCatalog {
    const val BUNDLED_ID = "bundled-hosts"

    val sources =
        listOf(
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
        )

    fun byId(id: String): BlocklistSource? = sources.firstOrNull { it.id == id }
}
