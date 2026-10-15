package com.github.ten951.gitplus.blame

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class BlameQueryCoordinatorTest {
    @Test fun finalConsumerCancelsWorkAndNextRequestCanRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val expected = mapOf(0 to BlameLine(uncommitted = true))
        val coordinator = BlameQueryCoordinator(scope) {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            expected
        }
        try {
            val request = BlameRequest(Path.of("/repo/file"), "text")
            val consumer = async { coordinator.query(request) }
            withTimeout(5_000) { entered.await() }
            consumer.cancelAndJoin()
            withTimeout(5_000) { stopped.await() }
            assertSame(expected, withTimeout(5_000) { coordinator.query(request) })
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun consumersShareOneQueryAndOneCancellationDoesNotCancelTheOther() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val expected = mapOf(0 to BlameLine(uncommitted = true))
        val coordinator = BlameQueryCoordinator(scope) {
            calls.incrementAndGet(); entered.complete(Unit); release.await(); expected
        }
        try {
            val request = BlameRequest(Path.of("/repo/file"), "text")
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.query(request) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.query(request) }
            first.cancelAndJoin()
            release.complete(Unit)
            assertSame(expected, second.await())
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }
}
