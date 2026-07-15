package com.nubasu.nuchematica.renderer.section

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal sealed interface WorkerCompletion<out O> {
    data class Success<O>(internal val value: O) : WorkerCompletion<O>

    data class Failure(internal val throwable: Throwable) : WorkerCompletion<Nothing>
}

private class ActiveWorkerJob {
    internal var cancelled: Boolean = false
    internal var started: Boolean = false
    internal var future: Future<*>? = null
}

internal class SingleSlotWorker<I, O : Any>(
    threadName: String,
    private val operation: (I) -> O,
    private val discard: (O) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    },
) : AutoCloseable {
    private val lock: Any = Any()
    private var active: ActiveWorkerJob? = null
    private var completion: WorkerCompletion<O>? = null
    private var closed: Boolean = false

    internal val inFlightCount: Int
        get() = synchronized(lock) { if (active == null) 0 else 1 }

    internal val pendingCompletionCount: Int
        get() = synchronized(lock) { if (completion == null) 0 else 1 }

    internal fun submit(input: I): Boolean {
        val job = synchronized(lock) {
            if (closed || active != null) return false
            ActiveWorkerJob().also { active = it }
        }

        try {
            val future = executor.submit { runJob(job, input) }
            synchronized(lock) {
                job.future = future
                if (job.cancelled) {
                    future.cancel(true)
                    if (!job.started && active === job) {
                        active = null
                    }
                }
            }
        } catch (throwable: Throwable) {
            synchronized(lock) {
                if (active === job) {
                    active = null
                }
            }
            throw throwable
        }
        return true
    }

    internal fun pollCompletion(): WorkerCompletion<O>? {
        return synchronized(lock) {
            val result = completion ?: return null
            completion = null
            active = null
            result
        }
    }

    internal fun cancelCurrent(): Unit {
        var discarded: O? = null
        var discardJob: ActiveWorkerJob? = null
        synchronized(lock) {
            val job = active ?: return
            job.cancelled = true
            job.future?.cancel(true)
            val queued = completion
            if (queued != null) {
                if (queued is WorkerCompletion.Success) {
                    discarded = queued.value
                    discardJob = job
                } else {
                    active = null
                }
                completion = null
            } else if (!job.started && job.future != null) {
                active = null
            }
        }
        val output = discarded
        if (output != null) {
            try {
                discard(output)
            } finally {
                synchronized(lock) {
                    if (active === discardJob) {
                        active = null
                    }
                }
            }
        }
    }

    override fun close(): Unit {
        var discarded: O? = null
        var discardJob: ActiveWorkerJob? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            val job = active
            if (job != null) {
                job.cancelled = true
                job.future?.cancel(true)
                if (!job.started && job.future != null) {
                    active = null
                }
            }
            val queued = completion
            if (queued is WorkerCompletion.Success) {
                discarded = queued.value
                discardJob = job
            }
            if (queued != null) {
                completion = null
                if (discarded == null) {
                    active = null
                }
            }
        }
        try {
            val output = discarded
            if (output != null) {
                try {
                    discard(output)
                } finally {
                    synchronized(lock) {
                        if (active === discardJob) {
                            active = null
                        }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runJob(job: ActiveWorkerJob, input: I): Unit {
        val shouldRun = synchronized(lock) {
            job.started = true
            if (closed || job.cancelled || active !== job) {
                if (active === job) {
                    active = null
                }
                false
            } else {
                true
            }
        }
        if (!shouldRun) return

        var output: O? = null
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            output = operation(input)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()

            var discardOutput = false
            synchronized(lock) {
                if (closed || job.cancelled || active !== job) {
                    discardOutput = true
                } else {
                    check(completion == null) { "single-slot worker completion overflow" }
                    completion = WorkerCompletion.Success(checkNotNull(output))
                    output = null
                }
            }
            if (discardOutput) {
                val discardedOutput = output
                output = null
                try {
                    discardedOutput?.let(discard)
                } finally {
                    synchronized(lock) {
                        if (active === job) {
                            active = null
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            try {
                output?.let(discard)
            } catch (discardFailure: Throwable) {
                throwable.addSuppressed(discardFailure)
            }
            synchronized(lock) {
                if (closed || job.cancelled || active !== job) {
                    if (active === job) {
                        active = null
                    }
                } else {
                    check(completion == null) { "single-slot worker completion overflow" }
                    completion = WorkerCompletion.Failure(throwable)
                }
            }
        }
    }
}
