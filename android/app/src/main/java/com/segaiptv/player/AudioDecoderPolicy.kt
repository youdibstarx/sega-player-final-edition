package com.segaiptv.player

/**
 * Favour FFmpeg audio only for TV/TV-box devices. Mobile hardware audio stays
 * the default because the same 4K streams already have sound on phones.
 *
 * Some generic Android TV boxes omit the leanback / television feature flags
 * and report UI_MODE_TYPE_NORMAL. A device with no touchscreen is treated as
 * a likely TV box to cover those devices.
 */
internal object AudioDecoderPolicy {
    fun preferFfmpeg(
        hasLeanback: Boolean,
        tvUiMode: Boolean,
        hasTelevisionFeature: Boolean,
        hasTouchscreen: Boolean
    ): Boolean = hasLeanback || tvUiMode || hasTelevisionFeature || !hasTouchscreen
}
