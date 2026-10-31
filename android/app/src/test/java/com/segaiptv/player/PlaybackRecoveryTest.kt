package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun failedDecoderIsNeverRetriedForever() {
        val recovery = DecoderRecovery()
        assertFalse(recovery.reject(null))
        assertTrue(recovery.reject("hardware"))
        assertFalse(recovery.reject("hardware"))
        assertTrue(recovery.reject("software"))
        assertTrue(recovery.reject("third"))
        assertFalse(recovery.reject("fourth"))
        assertFalse(DecoderRecovery.network("ERROR_CODE_DECODING_FAILED"))
        assertTrue(DecoderRecovery.decoding("ERROR_CODE_DECODING_FAILED"))
        assertTrue(DecoderRecovery.network("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
    }
    @Test fun refreshSourceChangeAndRevocationDiscardRetainedData() {
        val memory=CategoryMemory()
        val source=Playlist(1,"Test","https://provider.test/a.m3u")
        val snapshot=memory.page(listOf(source),0,"live")
        snapshot.selectedKey="7"
        snapshot.entries=emptyList()
        assertSame(snapshot,memory.page(listOf(source),0,"live"))
        assertNull(memory.page(listOf(source),1,"live").entries)
        assertNull(memory.page(listOf(source.copy(url="https://provider.test/b.m3u")),1,"live").selectedKey)
        memory.clear()
        assertNull(memory.page(listOf(source),0,"live").entries)
    }
}
