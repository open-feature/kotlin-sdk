package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TIMEOUT_SECONDS = 5L

@OptIn(ExperimentalIsolatedApi::class, ExperimentalCoroutinesApi::class)
class ProviderLifecycleDispatchTest {

    @AfterTest
    fun tearDown() {
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    private class ImmediateDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = false

        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()

        override fun limitedParallelism(parallelism: Int): CoroutineDispatcher = this
    }

    private class BlockingInitProvider(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : NoOpProvider() {
        override suspend fun initialize(initialContext: EvaluationContext?) {
            entered.countDown()
            release.await()
        }
    }

    @Test
    fun initializeOnAnImmediateDispatcherDoesNotRunWithTheStateLockHeld() {
        val instance = createOpenFeatureAPIInstance()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = BlockingInitProvider(entered, release)
        val probe = Executors.newSingleThreadExecutor()

        val setter = thread { instance.setProvider(provider, ImmediateDispatcher()) }
        try {
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "initialize never ran")
            val observed = probe.submit<FeatureProvider> { instance.getProvider() }
            assertSame(provider, observed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            probe.shutdown()
            setter.join()
        }
    }
}