package com.alraad.core.adblocker.impl

/** Generic cosmetic filtering CSS; host-specific rules can be layered later. */
object CosmeticRules {
    private val genericSelectors =
        listOf(
            ".ad", ".ads", ".advert", ".advertisement", ".banner-ad",
            "[id^='ad-']", "[class^='ad-']", "[class*=' ad-']", "iframe[src*='doubleclick']",
        )

    @Suppress("UnusedParameter") // host-specific rules layered here later
    fun forHost(host: String): String = genericSelectors.joinToString(", ") + " { display: none !important; }"
}
