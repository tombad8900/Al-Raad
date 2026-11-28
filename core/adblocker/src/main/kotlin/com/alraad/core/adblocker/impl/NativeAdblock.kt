package com.alraad.core.adblocker.impl

/**
 * JNI entry points of libalraad_adblock (Brave's adblock-rust, built from core/adblocker/native).
 * A handle is an opaque engine pointer; 0 means "no engine".
 */
internal object NativeAdblock {
    private val loaded: Boolean =
        try {
            System.loadLibrary("alraad_adblock")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    /** True when the native library could be loaded on this device or JVM. */
    fun isAvailable(): Boolean = loaded

    @JvmStatic external fun nativeCreate(listText: String): Long

    @JvmStatic external fun nativeShouldBlock(
        handle: Long,
        url: String,
        sourceUrl: String,
        requestType: String,
    ): Boolean

    @JvmStatic external fun nativeDestroy(handle: Long)
}
