package edu.jhu.cobra.commons.graph.storage

import edu.jhu.cobra.commons.value.IntVal
import edu.jhu.cobra.commons.value.intVal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        storage.addRevertListener { _, _ -> heldDuringRevert.add(lock.isHeldByCurrentThread) }
        storage.addNode()
        storage.clearActiveLayer()
        storage.addNode()
        storage.clear()
        assertEquals(listOf(true, true), heldDuringRevert)
    }
}
