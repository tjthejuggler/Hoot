package com.example.hoot.domain.nutrition

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sliding-minute rate-guard window math + multi-food batch panel parsing. */
class LlmRateGuardTest {

    /** Deterministic virtual clock: [nowMs] advanced by the sleep hook. */
    private class Clock(var now: Long = 0L) {
        val sleeps = mutableListOf<Long>()
        suspend fun sleep(ms: Long) { sleeps += ms; now += ms }
    }

    private fun guard(callsPerMinute: Int, clock: Clock) =
        LlmRateGuard(callsPerMinute, nowMs = { clock.now }, sleepMs = { clock.sleep(it) })

    @Test fun `unlimited when callsPerMinute is zero`() = runBlocking {
        val clock = Clock()
        val g = guard(0, clock)
        repeat(50) { assertEquals(0L, g.acquire()) }
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test fun `calls under the budget never wait`() = runBlocking {
        val clock = Clock()
        val g = guard(20, clock)
        repeat(20) { assertEquals(0L, g.acquire()) }
        assertTrue(clock.sleeps.isEmpty())
        assertEquals(20, g.callsInWindow())
    }

    @Test fun `call 21 waits until the oldest call ages out`() = runBlocking {
        val clock = Clock()
        val g = guard(20, clock)
        repeat(20) { g.acquire() }                // all at t=0
        clock.now += 30_000                       // halfway through the window
        val waited = g.acquire()                  // pause = time to window boundary
        assertEquals(30_000L, waited)
        // At t=60_000 the whole t=0 batch has aged out; only the new call remains.
        assertEquals(1, g.callsInWindow())
        // Window fully clear: no more waiting for the next burst.
        assertEquals(0L, g.acquire())
    }

    @Test fun `window slides fully after a minute of silence`() = runBlocking {
        val clock = Clock()
        val g = guard(5, clock)
        repeat(5) { g.acquire() }
        clock.now += 60_001
        assertEquals(0, g.callsInWindow())
        assertEquals(0L, g.acquire())
    }

    @Test fun `burst-then-drain math stays bounded`() = runBlocking {
        val clock = Clock()
        val g = guard(10, clock)
        repeat(10) { g.acquire() }                // t=0, fills the window
        repeat(5) { g.acquire() }                 // waits until t=0 ages out
        // All 5 over-budget calls share ONE aging-out wait (same virtual
        // instant), then each acquires at the slid timestamp — the total delay
        // never exceeds one window even when several calls over-budget.
        assertTrue(clock.sleeps.isNotEmpty())
        assertTrue(clock.sleeps.sum() in 1..60_000L)
        assertEquals(5, g.callsInWindow())
    }

    @Test fun `total delay for a 74-food backlog respects the budget`() = runBlocking {
        // 74 foods at batch size 10 → 8 batched calls; budget 20/min → 0 waits.
        val clock = Clock()
        val g = guard(20, clock)
        repeat(8) { assertEquals(0L, g.acquire()) }
        assertTrue(clock.sleeps.isEmpty())
    }
}
