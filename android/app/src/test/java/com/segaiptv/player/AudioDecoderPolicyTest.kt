package com.segaiptv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderPolicyTest {
    @Test fun phoneKeepsPlatformDecoder() {
        assertFalse(AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = false, tvUiMode = false,
            hasTelevisionFeature = false, hasTouchscreen = true
        ))
    }

    @Test fun certifiedAndroidTvPrefersFfmpeg() {
        assertTrue(AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = true, tvUiMode = true,
            hasTelevisionFeature = true, hasTouchscreen = false
        ))
    }

    @Test fun genericTouchlessTvBoxPrefersFfmpeg() {
        assertTrue(AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = false, tvUiMode = false,
            hasTelevisionFeature = false, hasTouchscreen = false
        ))
    }

    @Test fun tvUiModeOverridesTouchscreen() {
        assertTrue(AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = false, tvUiMode = true,
            hasTelevisionFeature = false, hasTouchscreen = true
        ))
    }
}
