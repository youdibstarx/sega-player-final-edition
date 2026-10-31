package com.segaiptv.player

internal class DecoderRecovery {
    val excluded = linkedSetOf<String>()
    fun reject(name: String?): Boolean {
        if (name.isNullOrBlank() || name in excluded || excluded.size >= 3) return false
        excluded += name
        return true
    }
    companion object {
        fun network(error: String) = error in setOf("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", "ERROR_CODE_IO_BAD_HTTP_STATUS",
            "ERROR_CODE_TIMEOUT", "ERROR_CODE_IO_UNSPECIFIED")
        fun decoding(error: String) = error in setOf("ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_DECODER_INIT_FAILED", "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES")
    }
}
