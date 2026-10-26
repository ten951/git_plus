package com.github.ten951.gitplus.blame

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.Charset
import java.nio.file.Path

data class BlameRequest(val file: Path, val content: String, val charset: Charset = Charsets.UTF_8,
                        val separator: String = "\n", val bom: List<Byte> = emptyList(), val generation: Long = 0)

/** Shared work belongs to the service, not the first editor; cancel when the last consumer leaves. */
class BlameQueryCoordinator(private val scope: CoroutineScope,
                            private val load: suspend (BlameRequest) -> Map<Int, BlameLine>) {
    private val shared = SharedQueryCoordinator(scope, load)
    suspend fun query(request: BlameRequest): Map<Int, BlameLine> = shared.query(request)
}

internal class SharedQueryCoordinator<K : Any, V>(private val scope: CoroutineScope, private val load: suspend (K) -> V) {
    private class Flight<V>(val result: Deferred<V>, var consumers: Int = 0)
    private val mutex = Mutex()
    private val flights = mutableMapOf<K, Flight<V>>()

    suspend fun query(request: K): V {
        val flight = mutex.withLock {
            flights.getOrPut(request) { Flight(scope.async(Dispatchers.Default) { load(request) }) }.also { it.consumers++ }
        }
        try { return flight.result.await() }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.consumers--
                    if (flight.consumers == 0) {
                        if (flights[request] === flight) flights.remove(request)
                        if (!flight.result.isCompleted) flight.result.cancel()
                    }
                }
            }
        }
    }
}
