package com.nubasu.nuchematica.renderer.section

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

public class SingleSlotWorkerTest {

    @Test
    public fun queuedAndRunningWorkNeverExceedsOneSlot(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeOperations = AtomicInteger()
        val maximumOperations = AtomicInteger()
        val worker = SingleSlotWorker<Int, Int>(
            threadName = "single-slot-max-test",
            operation = { value ->
                val active = activeOperations.incrementAndGet()
                maximumOperations.accumulateAndGet(active, ::maxOf)
                entered.countDown()
                try {
                    release.await()
                    value * 2
                } finally {
                    activeOperations.decrementAndGet()
                }
            },
            discard = {},
        )
        try {
            assertTrue(worker.submit(2))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.submit(3))
            assertEquals(1, worker.inFlightCount)

            release.countDown()
            val completion = awaitCompletion(worker)
            assertEquals(4, (completion as WorkerCompletion.Success).value)
            assertEquals(1, maximumOperations.get())
            assertEquals(0, worker.inFlightCount)
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    public fun exceptionReturnsTheSlotAfterCompletionIsObserved(): Unit {
        val attempted = CountDownLatch(1)
        val worker = SingleSlotWorker<Unit, Int>(
            threadName = "single-slot-failure-test",
            operation = {
                attempted.countDown()
                throw IllegalStateException("boom")
            },
            discard = {},
        )
        try {
            assertTrue(worker.submit(Unit))
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            val completion = awaitCompletion(worker)
            assertTrue(completion is WorkerCompletion.Failure)
            assertEquals("boom", (completion as WorkerCompletion.Failure).throwable.message)
            assertEquals(0, worker.inFlightCount)
            assertTrue(worker.submit(Unit))
        } finally {
            worker.close()
        }
    }

    @Test
    public fun cancellationDiscardsLateOutputWithoutGrowingTheCompletionQueue(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discardEntered = CountDownLatch(1)
        val releaseDiscard = CountDownLatch(1)
        val discarded = AtomicInteger()
        val worker = SingleSlotWorker<Unit, Int>(
            threadName = "single-slot-cancel-test",
            operation = {
                entered.countDown()
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                        // Simulate an operation that observes cancellation only after producing output.
                    }
                }
                7
            },
            discard = {
                discardEntered.countDown()
                releaseDiscard.await()
                discarded.incrementAndGet()
            },
        )
        try {
            assertTrue(worker.submit(Unit))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.cancelCurrent()
            assertFalse(worker.submit(Unit), "cancelled running work still owns the single slot")

            release.countDown()
            assertTrue(discardEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, worker.inFlightCount)
            assertFalse(worker.submit(Unit), "discarding output still owns the single slot")
            releaseDiscard.countDown()
            awaitCondition { worker.inFlightCount == 0 }
            assertEquals(1, discarded.get())
            assertEquals(0, worker.pendingCompletionCount)
            assertTrue(worker.submit(Unit))
        } finally {
            release.countDown()
            releaseDiscard.countDown()
            worker.close()
        }
    }

    @Test
    public fun queuedCancellationKeepsTheSlotUntilDiscardCompletes(): Unit {
        val discardEntered = CountDownLatch(1)
        val releaseDiscard = CountDownLatch(1)
        val worker = SingleSlotWorker<Unit, Int>(
            threadName = "single-slot-queued-cancel-test",
            operation = { 9 },
            discard = {
                discardEntered.countDown()
                releaseDiscard.await()
            },
        )
        val cancelThread = Thread(worker::cancelCurrent, "single-slot-cancel-caller")
        try {
            assertTrue(worker.submit(Unit))
            awaitCondition { worker.pendingCompletionCount == 1 }

            cancelThread.start()
            assertTrue(discardEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, worker.inFlightCount)
            assertFalse(worker.submit(Unit), "queued output retains the slot while discard is running")

            releaseDiscard.countDown()
            cancelThread.join(TimeUnit.SECONDS.toMillis(2L))
            assertFalse(cancelThread.isAlive)
            assertEquals(0, worker.inFlightCount)
            assertEquals(0, worker.pendingCompletionCount)
            assertTrue(worker.submit(Unit))
        } finally {
            releaseDiscard.countDown()
            cancelThread.join(TimeUnit.SECONDS.toMillis(2L))
            worker.close()
        }
    }

    @Test
    public fun cancellationBeforeWorkerStartReturnsTheSlotWithoutRunningTheOperation(): Unit {
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            blockerEntered.countDown()
            releaseBlocker.await()
        }
        val operations = AtomicInteger()
        val worker = SingleSlotWorker<Unit, Int>(
            threadName = "single-slot-pre-start-cancel-test",
            operation = { operations.incrementAndGet() },
            discard = {},
            executor = executor,
        )
        try {
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
            assertTrue(worker.submit(Unit))

            worker.cancelCurrent()

            assertEquals(0, worker.inFlightCount)
            assertEquals(0, worker.pendingCompletionCount)
            assertTrue(worker.submit(Unit))
            releaseBlocker.countDown()
            assertEquals(1, (awaitCompletion(worker) as WorkerCompletion.Success).value)
            assertEquals(1, operations.get())
        } finally {
            releaseBlocker.countDown()
            worker.close()
        }
    }

    @Test
    public fun terminalCloseDiscardsQueuedCompletionAndRejectsNewWork(): Unit {
        val discarded = AtomicInteger()
        val worker = SingleSlotWorker<Int, Int>(
            threadName = "single-slot-close-test",
            operation = { it },
            discard = { discarded.incrementAndGet() },
        )
        assertTrue(worker.submit(5))
        awaitCondition { worker.pendingCompletionCount == 1 }

        worker.close()

        assertEquals(1, discarded.get())
        assertEquals(0, worker.pendingCompletionCount)
        assertEquals(0, worker.inFlightCount)
        assertFalse(worker.submit(6))
    }

    private fun <O : Any> awaitCompletion(worker: SingleSlotWorker<*, O>): WorkerCompletion<O> {
        var result: WorkerCompletion<O>? = null
        awaitCondition {
            result = worker.pollCompletion()
            result != null
        }
        return checkNotNull(result)
    }

    private fun awaitCondition(condition: () -> Boolean): Unit {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for worker state" }
            Thread.yield()
        }
    }
}
