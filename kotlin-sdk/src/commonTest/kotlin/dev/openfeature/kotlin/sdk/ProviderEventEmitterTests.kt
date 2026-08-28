package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEventEmitterTests {

    private suspend fun collectFrom(emitter: ProviderEventEmitter, into: MutableList<OpenFeatureProviderEvents>) =
        emitter.observe().collect { into.add(it) }

    @Test
    fun initializingReportsReadyOnSuccess() = runTest {
        val emitter = ProviderEventEmitter()
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()

        emitter.initializing { }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf(OpenFeatureProviderEvents.ProviderReady::class), seen.map { it::class })
    }

    @Test
    fun initializingReportsErrorBeforeRethrowing() = runTest {
        val emitter = ProviderEventEmitter()
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()

        assertFailsWith<OpenFeatureError.ProviderFatalError> {
            emitter.initializing { throw OpenFeatureError.ProviderFatalError("no recovering") }
        }
        runCurrent()
        job.cancelAndJoin()

        val error = assertIs<OpenFeatureProviderEvents.ProviderError>(seen.single())
        assertEquals(ErrorCode.PROVIDER_FATAL, error.eventDetails?.errorCode)
        assertEquals("no recovering", error.eventDetails?.message)
    }

    @Test
    fun readinessSurvivesALateSubscriber() = runTest {
        val emitter = ProviderEventEmitter()
        emitter.initializing { }

        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()
        job.cancelAndJoin()

        assertIs<OpenFeatureProviderEvents.ProviderReady>(seen.single())
    }

    @Test
    fun reconcilingBracketsTheOutcome() = runTest {
        val emitter = ProviderEventEmitter()
        emitter.initializing { }
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()
        seen.clear()

        emitter.reconciling { }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            seen.map { it::class }
        )
    }

    @Test
    fun overlappingReconciliationsReportOnceAtEachEnd() = runTest {
        val emitter = ProviderEventEmitter()
        emitter.initializing { }
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()
        seen.clear()

        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = launch { emitter.reconciling { releaseFirst.await() } }
        val second = launch { emitter.reconciling { releaseSecond.await() } }
        runCurrent()

        assertEquals(
            listOf(OpenFeatureProviderEvents.ProviderReconciling::class),
            seen.map { it::class }
        )

        releaseFirst.complete(Unit)
        first.join()
        runCurrent()
        assertEquals(1, seen.size)

        releaseSecond.complete(Unit)
        second.join()
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            seen.map { it::class }
        )
    }

    @Test
    fun aFailedReconciliationReportsTheErrorAndRethrows() = runTest {
        val emitter = ProviderEventEmitter()
        emitter.initializing { }
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()
        seen.clear()

        assertFailsWith<OpenFeatureError.InvalidContextError> {
            emitter.reconciling { throw OpenFeatureError.InvalidContextError("bad targeting key") }
        }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderError::class
            ),
            seen.map { it::class }
        )
    }

    @Test
    fun aFullyCancelledReconciliationReportsThePrecedingTransitionAgain() = runTest {
        val emitter = ProviderEventEmitter()
        emitter.initializing { }
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()
        seen.clear()

        val reconciliation = launch { emitter.reconciling { CompletableDeferred<Unit>().await() } }
        runCurrent()
        reconciliation.cancelAndJoin()
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderReady::class
            ),
            seen.map { it::class }
        )
    }

    @Test
    fun spontaneousTransitionsAreReportedInOrder() = runTest {
        val emitter = ProviderEventEmitter()
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()

        emitter.emit(OpenFeatureProviderEvents.ProviderStale())
        emitter.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        emitter.emit(OpenFeatureProviderEvents.ProviderReady())
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderConfigurationChanged::class,
                OpenFeatureProviderEvents.ProviderReady::class
            ),
            seen.map { it::class }
        )
    }

    @Test
    fun aStatelessEventIsNotWhatACancelledReconciliationRestores() = runTest {
        val emitter = ProviderEventEmitter()
        emitter.initializing { }
        emitter.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { collectFrom(emitter, seen) }
        runCurrent()
        seen.clear()

        val reconciliation = launch { emitter.reconciling { CompletableDeferred<Unit>().await() } }
        runCurrent()
        reconciliation.cancelAndJoin()
        runCurrent()
        job.cancelAndJoin()

        assertIs<OpenFeatureProviderEvents.ProviderReady>(seen.last())
    }
}