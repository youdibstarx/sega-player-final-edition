package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProgressiveLibraryTest {
    @Test fun firstBatchIsVisibleBeforeTheSourceEndsWithoutChangingOrder() {
        var consumed = 0
        val snapshots = mutableListOf<List<Channel>>()
        val lines = sequence {
            yield("#EXTM3U")
            for (i in 1..1000) {
                yield("#EXTINF:-1 group-title=\"${if (i < 100) "First" else "Second"}\",Entry $i")
                consumed = i
                yield("https://provider.test/live/$i.ts?original=yes")
            }
        }
        val result = M3uParser.parseLines(lines,"Provider","https://provider.test/list.m3u", onPartial = {
            if (snapshots.isEmpty()) assertTrue("Must publish before EOF", consumed < 1000)
            snapshots += it
        })
        assertEquals(30,snapshots.first().size)
        assertEquals(1000,result.size)
        assertEquals("First",result.first().category)
        assertEquals("Second",result.last().category)
        assertEquals("https://provider.test/live/1.ts?original=yes",result.first().url)
        assertEquals((1..1000).map { "Entry $it" },result.map { it.name })
        assertEquals(30,snapshots.first().size) // A published snapshot must not mutate.
    }
    @Test fun cancellationStopsReadingLargeSources() {
        var checks = 0
        try {
            M3uParser.parseLines(generateSequence { "# comment" }, "P", "", check = {
                if (++checks == 50) throw kotlinx.coroutines.CancellationException()
            })
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { assertEquals(50,checks) }
    }
    @Test fun providerExpiryNeverInventsMissingOrInvalidDates() {
        assertFalse(SubscriptionRepository.parse("""{"user_info":{"auth":1,"status":"Active"}}""").unlimited)
        assertTrue(SubscriptionRepository.parse("""{"user_info":{"auth":1,"exp_date":null}}""").unlimited)
        assertFalse(SubscriptionRepository.parse("""{"user_info":{"auth":0,"exp_date":"1800000000"}}""").available)
        assertNull(SubscriptionRepository.parse("""{"user_info":{"auth":1,"exp_date":"bad"}}""").expiry)
        assertEquals(1800000000L,SubscriptionRepository.parse("""{"user_info":{"auth":1,"exp_date":"1800000000"}}""").expiry)
        assertEquals(1L,SubscriptionRepository.daysRemaining(1001,1000))
        assertEquals(0L,SubscriptionRepository.daysRemaining(1000,1000))
    }
    @Test fun weatherSelectsNearestForecastHourAndRejectsStaleData() {
        val raw = """{"properties":{"timeseries":[{"time":"2026-11-06T12:00:00Z","data":{"instant":{"details":{"air_temperature":21.5}},"next_1_hours":{"summary":{"symbol_code":"clearsky_day"}}}}]}}"""
        val weather = WeatherRepository.parse(raw, Instant.parse("2026-11-06T12:10:00Z"))
        assertEquals(21.5, weather.temperature, .01)
        assertEquals("☀",WeatherRepository.icon(weather.symbol))
        try { WeatherRepository.parse(raw,Instant.parse("2026-11-07T12:00:00Z")); fail("Stale forecast") }
        catch (_: IllegalArgumentException) { }
    }
}
