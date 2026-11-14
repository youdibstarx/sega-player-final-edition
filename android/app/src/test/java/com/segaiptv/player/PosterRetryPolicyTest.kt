package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class PosterRetryPolicyTest {
    @Test fun oneMetadataRecoveryAllowedThenOnlyTransientFailuresRetry() {
        assertTrue(PosterRetryPolicy.retryAllowed(0, Exception("HTTP 404")))
        assertFalse(PosterRetryPolicy.retryAllowed(1, Exception("HTTP 404")))
        assertFalse(PosterRetryPolicy.retryAllowed(1, Exception("HTTP 403")))
        assertFalse(PosterRetryPolicy.retryAllowed(1, SSLHandshakeException("bad certificate")))
        assertTrue(PosterRetryPolicy.retryAllowed(1, SocketTimeoutException("read timed out")))
        assertFalse(PosterRetryPolicy.retryAllowed(2, SocketTimeoutException("read timed out")))
    }

    @Test fun logsCategorizeFailuresWithoutLoggingProviderUrls() {
        val exception = Exception("HTTP 503 https://provider.test/get.php?username=secret")
        assertEquals("HTTP_503", PosterRetryPolicy.failureCategory(exception))
        assertEquals("TIMEOUT", PosterRetryPolicy.failureCategory(SocketTimeoutException("read timed out")))
        assertTrue(PosterRetryPolicy.delayMillis(2) > PosterRetryPolicy.delayMillis(1))
    }
}
