package com.ktu.aigaleri.work

import androidx.work.WorkInfo
import com.ktu.aigaleri.ui.IndexRunState
import org.junit.Assert.assertEquals
import org.junit.Test

/** T-016 QA: WorkInfo durumlarından IndexRunState türetmesi; tüm kombinasyonlar ve uç boyutlar. */
class IndexWorkRunStateQaTest {
    private val all = WorkInfo.State.entries

    private fun oracle(s: Set<WorkInfo.State>) = when {
        WorkInfo.State.RUNNING in s -> IndexRunState.RUNNING
        WorkInfo.State.ENQUEUED in s || WorkInfo.State.BLOCKED in s -> IndexRunState.WAITING
        else -> IndexRunState.IDLE
    }

    @Test
    fun allSixStatesAreCovered() {
        assertEquals(
            setOf("ENQUEUED", "RUNNING", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED"),
            all.map { it.name }.toSet(),
        )
    }

    /** 2^6 alt küme: runState kuraldan sapmaz; isActive == (runState != IDLE). */
    @Test
    fun everySubsetOfStates_matchesRule_andIsActiveIsConsistent() {
        for (mask in 0 until (1 shl all.size)) {
            val subset = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val expected = oracle(subset.toSet())
            assertEquals("subset=$subset", expected, IndexWork.runState(subset))
            assertEquals("subset=$subset", expected != IndexRunState.IDLE, IndexWork.isActive(subset))
        }
    }

    @Test
    fun emptyList_isIdle_andNotActive() {
        assertEquals(IndexRunState.IDLE, IndexWork.runState(emptyList()))
        assertEquals(IndexRunState.IDLE, IndexWork.runState(emptySet()))
        assertEquals(false, IndexWork.isActive(emptySet()))
    }

    @Test
    fun onlyBlocked_isWaiting_andActive() {
        assertEquals(IndexRunState.WAITING, IndexWork.runState(listOf(WorkInfo.State.BLOCKED, WorkInfo.State.BLOCKED)))
        assertEquals(true, IndexWork.isActive(listOf(WorkInfo.State.BLOCKED)))
    }

    @Test
    fun runningPlusEnqueued_isRunning_inEitherOrder() {
        assertEquals(IndexRunState.RUNNING, IndexWork.runState(listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)))
        assertEquals(IndexRunState.RUNNING, IndexWork.runState(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)))
    }

    @Test
    fun terminalStatesOnly_areIdle_evenWhenMany() {
        val terminals = listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)
        assertEquals(IndexRunState.IDLE, IndexWork.runState(terminals))
        assertEquals(IndexRunState.IDLE, IndexWork.runState(List(100_000) { terminals[it % 3] }))
    }

    @Test
    fun hugeList_withSingleRunningAtEnd_isRunning() {
        val big = List(100_000) { WorkInfo.State.SUCCEEDED } + WorkInfo.State.RUNNING
        assertEquals(IndexRunState.RUNNING, IndexWork.runState(big))
        val waiting = List(100_000) { WorkInfo.State.CANCELLED } + WorkInfo.State.ENQUEUED
        assertEquals(IndexRunState.WAITING, IndexWork.runState(waiting))
    }
}
