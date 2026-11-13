package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.BackgroundCadence
import com.coloros16.liquidglass.hook.BackdropWorkBudget
import com.coloros16.liquidglass.hook.CaptureDemandPolicy
import org.junit.Assert.*
import org.junit.Test

class BackdropLatencyTest {
    @Test fun revealShortensAnAlreadyWaitingCaptureDeadline() {
        val cadence=BackgroundCadence()
        repeat(8) {cadence.observe(900L+it,false)}
        assertEquals(137L,CaptureDemandPolicy.waitMs(1030,1000,cadence.admittedIntervalMs(1030,60,false,false)))
        cadence.reveal(1030)
        assertEquals(3L,CaptureDemandPolicy.waitMs(1030,1000,cadence.admittedIntervalMs(1030,60,false,false)))
        assertEquals(0L,CaptureDemandPolicy.waitMs(1033,1000,cadence.admittedIntervalMs(1033,60,false,false)))
    }

    @Test fun changedPixelsAndNativeEventsCannotExtendTheFastLane() {
        val cadence=BackgroundCadence();cadence.reveal(1000)
        for(now in 1000L..2000L) {
            cadence.observe(now,true);cadence.event(now)
            assertEquals(if(now<1160)33L else 100L,cadence.admittedIntervalMs(now,120,true,false))
        }
    }

    @Test fun idleAndImmediateBudgetsRemainBounded() {
        val cadence=BackgroundCadence();cadence.reveal(1000)
        repeat(8) {cadence.observe(1200L+it,false)}
        assertEquals(167L,cadence.admittedIntervalMs(1400,120,false,false))
        assertEquals(66L,cadence.admittedIntervalMs(1400,120,true,true))
        assertEquals(1000L,cadence.admittedIntervalMs(1400,1,true,false))
        assertEquals(0L,CaptureDemandPolicy.waitMs(1,0,100))
        assertEquals(100L,CaptureDemandPolicy.waitMs(900,1000,100))
    }

    @Test fun initialGpuMaterialsDoNotWaitBehindAnArtificialPerStyleGap() {
        val budget=BackdropWorkBudget()
        assertEquals(0L,budget.waitMs(1000));budget.started(1000)
        assertEquals(0L,budget.waitMs(1001));budget.started(1001)
        assertEquals(48L,budget.waitMs(1002))
        assertEquals(0L,budget.waitMs(1050));budget.started(1050)
        assertEquals(50L,budget.waitMs(1050))
    }

    @Test fun gpuBudgetCannotBeBypassedByRepeatedPollsOrLongIdleTime() {
        val budget=BackdropWorkBudget();var count=0
        for(now in 1000L until 2000L) {
            repeat(5) {if(budget.waitMs(now)==0L) {budget.started(now);count++}}
        }
        assertEquals(21,count) // two initial credits plus 19 replenished credits
        budget.started(10000);budget.started(10000)
        assertEquals(50L,budget.waitMs(10000))
    }
}
