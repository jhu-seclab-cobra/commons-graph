package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.intVal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/*
 * Black-box tests for IStorage.criticalSection and criticalSectionLock (design-storage.md,
 * Storage critical section).
 *
 * - `single-threaded storage has no critical section lock` -- NativeStorageImpl returns null
 * - `criticalSection on single-threaded storage runs the action` -- unguarded, value returned
 * - `thread-safe storages expose their write lock` -- same WriteLock instance on every read
 * - `criticalSection holds the storage lock` -- held by the calling thread inside the action
 * - `nested criticalSection on the same storage proceeds` -- reentrant
 * - `criticalSection makes a read-modify-write atomic` -- concurrent increments lose no update
 * - `revert listeners run while the storage lock is held` -- clearActiveLayer and clear
 * - `an overflow through nested sections releases the lock` -- every level of a deep recursion
 * - `an overflow inside one section releases the lock` -- the action alone exhausts the stack
 * - `a section that catches the overflow of its nested sections holds the lock once` -- outer hold stays
 * - `an exception through nested sections releases the lock` -- control for the error cases
 * - `another thread enters the section after a thread overflowed in it` -- the lock is free for others
 * - `an overflow at the entry or the exit of a section releases the lock` -- the overflow lands in each part
 * - `an overflow inside a storage call of a section releases the lock` -- holds a storage call left behind
 */
internal class StorageCriticalSectionTest {
    private fun threadSafeStorages(): List<IStorage> = listOf(NativeConcurStorageImpl(), LayeredStorageImpl())

    @Test
    fun `single-threaded storage has no critical section lock`() {
        assertNull(NativeStorageImpl().criticalSectionLock)
    }

    @Test
    fun `criticalSection on single-threaded storage runs the action`() {
        val storage = NativeStorageImpl()
        val id = storage.criticalSection { storage.addNode() }
        assertTrue(storage.containsNode(id))
    }

    @Test
    fun `thread-safe storages expose their write lock`() {
        for (storage in threadSafeStorages()) {
            val lock = storage.criticalSectionLock
            assertIs<ReentrantReadWriteLock.WriteLock>(lock)
            assertSame(lock, storage.criticalSectionLock)
        }
    }

    @Test
    fun `criticalSection holds the storage lock`() {
        for (storage in threadSafeStorages()) {
            val lock = storage.criticalSectionLock as ReentrantReadWriteLock.WriteLock
            val held = storage.criticalSection { lock.isHeldByCurrentThread }
            assertTrue(held)
            assertFalse(lock.isHeldByCurrentThread)
        }
    }

    @Test
    fun `nested criticalSection on the same storage proceeds`() {
        for (storage in threadSafeStorages()) {
            val id = storage.criticalSection { storage.criticalSection { storage.addNode() } }
            assertTrue(storage.containsNode(id))
        }
    }

    @Test
    fun `criticalSection makes a read-modify-write atomic`() {
        val threadCount = 8
        val incrementsPerThread = 500
        for (storage in threadSafeStorages()) {
            storage.setMeta("counter", 0.intVal)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = CopyOnWriteArrayList<Throwable>()
            repeat(threadCount) {
                executor.submit {
                    try {
                        repeat(incrementsPerThread) {
                            storage.criticalSection {
                                val current = (storage.getMeta("counter") as IntVal).core.toInt()
                                storage.setMeta("counter", (current + 1).intVal)
                            }
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "increments timed out")
            assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
            assertEquals(threadCount * incrementsPerThread, (storage.getMeta("counter") as IntVal).core.toInt())
        }
    }

    @Test
    fun `revert listeners run while the storage lock is held`() {
        val storage = LayeredStorageImpl()
        val lock = storage.criticalSectionLock as ReentrantReadWriteLock.WriteLock
        val heldDuringRevert = mutableListOf<Boolean>()
        storage.addRevertListener { heldDuringRevert.add(lock.isHeldByCurrentThread) }
        storage.addNode()
        storage.clearActiveLayer()
        storage.addNode()
        storage.clear()
        assertEquals(listOf(true, true), heldDuringRevert)
    }

    // Runs [body] on a new thread with a small stack, so an unbounded recursion overflows quickly; returns what the
    // thread threw and, measured on that thread after the throw, the write lock's hold count.
    private fun onSmallStack(storage: IStorage, body: () -> Unit): Pair<Throwable?, Int> {
        val lock = storage.criticalSectionLock as ReentrantReadWriteLock.WriteLock
        val thrown = AtomicReference<Throwable?>()
        val holds = AtomicInteger(-1)
        val thread = Thread(null, {
            try {
                body()
            } catch (e: Throwable) {
                thrown.set(e)
            }
            holds.set(lock.holdCount)
        }, "small-stack", 256L * 1024)
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(30))
        assertFalse(thread.isAlive, "the overflowing thread did not finish")
        return thrown.get() to holds.get()
    }

    // Enters a critical section at every level until the stack overflows, as a recursive interpretation whose every
    // graph operation enters the section does.
    private fun descendInSections(storage: IStorage, depth: AtomicInteger): Unit = storage.criticalSection {
        depth.incrementAndGet()
        descendInSections(storage, depth)
    }

    private fun descend(depth: AtomicInteger) {
        depth.incrementAndGet()
        descend(depth)
    }

    @Test
    fun `01 an overflow through nested sections releases the lock`() {
        for (storage in threadSafeStorages()) {
            val depth = AtomicInteger()
            val (thrown, holds) = onSmallStack(storage) { descendInSections(storage, depth) }

            assertIs<StackOverflowError>(thrown)
            assertTrue(depth.get() > 1, "the recursion did not nest sections")
            assertEquals(0, holds, "the overflowing thread still holds the lock")
        }
    }

    @Test
    fun `02 an overflow inside one section releases the lock`() {
        for (storage in threadSafeStorages()) {
            val (thrown, holds) = onSmallStack(storage) { storage.criticalSection { descend(AtomicInteger()) } }

            assertIs<StackOverflowError>(thrown)
            assertEquals(0, holds)
        }
    }

    @Test
    fun `03 a section that catches the overflow of its nested sections holds the lock once`() {
        for (storage in threadSafeStorages()) {
            val lock = storage.criticalSectionLock as ReentrantReadWriteLock.WriteLock
            val holdsInCatch = AtomicInteger(-1)
            val (thrown, holds) = onSmallStack(storage) {
                val result = storage.criticalSection {
                    try {
                        descendInSections(storage, AtomicInteger())
                        "returned"
                    } catch (e: StackOverflowError) {
                        holdsInCatch.set(lock.holdCount)
                        "caught"
                    }
                }
                check(result == "caught")
            }

            assertEquals(null, thrown)
            assertEquals(1, holdsInCatch.get(), "the nested sections left holds behind")
            assertEquals(0, holds)
        }
    }

    @Test
    fun `04 an exception through nested sections releases the lock`() {
        for (storage in threadSafeStorages()) {
            val lock = storage.criticalSectionLock as ReentrantReadWriteLock.WriteLock
            val failure = IllegalStateException("inner")
            val thrown = runCatching {
                storage.criticalSection { storage.criticalSection { storage.criticalSection { throw failure } } }
            }.exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(0, lock.holdCount)
        }
    }

    @Test
    fun `05 another thread enters the section after a thread overflowed in it`() {
        for (storage in threadSafeStorages()) {
            onSmallStack(storage) { descendInSections(storage, AtomicInteger()) }

            val acquired = storage.criticalSectionLock!!.tryLock(5, TimeUnit.SECONDS)
            assertTrue(acquired, "the lock stayed held after the overflowing thread ended")
            storage.criticalSectionLock!!.unlock()
            val id = storage.criticalSection { storage.addNode() }
            assertTrue(storage.containsNode(id))
        }
    }

    // Runs [descent] to a stack overflow after [OVERFLOW_PADS] different numbers of extra frames, so that the overflow
    // lands at another place of the section's entry and exit each time; returns the first number of extra frames that
    // left a hold on the write lock of [storage], or null.
    private fun firstLeakingPad(storage: IStorage, descent: () -> Unit): Int? {
        val lock = storage.criticalSectionLock as ReentrantReadWriteLock.WriteLock
        val leakedAt = AtomicReference<Int?>()
        val thread = Thread(null, {
            for (pad in 0 until OVERFLOW_PADS) {
                try {
                    padded(pad, descent)
                } catch (_: StackOverflowError) {
                    // the overflow is the input
                }
                if (lock.holdCount != 0) {
                    leakedAt.set(pad)
                    break
                }
            }
        }, "small-stack", 256L * 1024)
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(60))
        assertFalse(thread.isAlive, "the overflowing thread did not finish")
        return leakedAt.get()
    }

    private fun padded(pad: Int, body: () -> Unit) {
        if (pad == 0) body() else padded(pad - 1, body)
    }

    // Recurses without a section around the recursion and enters one short section at every level, as an interpretation
    // does for each graph operation; the shallowest section is the only one open when the overflow reaches the section.
    private fun descendWithLeafSections(storage: IStorage) {
        storage.criticalSection { storage.setMeta("depth", 0.intVal) }
        descendWithLeafSections(storage)
    }

    @Test
    fun `06 an overflow at the entry or the exit of a section releases the lock`() {
        for (storage in threadSafeStorages()) {
            val leakedAt = firstLeakingPad(storage) { descendWithLeafSections(storage) }
            assertNull(leakedAt, "the lock stayed held after an overflow with $leakedAt extra frames")
        }
    }

    @Test
    fun `07 an overflow inside a storage call of a section releases the lock`() {
        for (storage in threadSafeStorages()) {
            val leakedAt = firstLeakingPad(storage) { descendInStorageCalls(storage) }
            assertNull(leakedAt, "the lock stayed held after an overflow with $leakedAt extra frames")
        }
    }

    // Enters a critical section at every level and writes through the storage inside it, whose own call takes the lock.
    private fun descendInStorageCalls(storage: IStorage): Unit = storage.criticalSection {
        storage.setMeta("depth", 0.intVal)
        descendInStorageCalls(storage)
    }

    private companion object {
        const val OVERFLOW_PADS = 600
    }
}
