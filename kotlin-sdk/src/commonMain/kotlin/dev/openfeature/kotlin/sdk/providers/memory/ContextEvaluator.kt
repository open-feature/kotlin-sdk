package dev.openfeature.kotlin.sdk.providers.memory

import dev.openfeature.kotlin.sdk.EvaluationContext

/**
 * Context evaluator - use for resolving flag according to evaluation context, for handling targeting.
 */
fun interface ContextEvaluator<T> {
    /**
     * Evaluates the flag's specific variant based on the provided evaluation context.
     *
     * @param flag the feature flag representation
     * @param evaluationContext the context used for targeting
     * @return the resolved variant key (a string matching a key in the flag's variants map),
     *         or null if no match was found.
     */
    fun evaluate(flag: Flag<T>, evaluationContext: EvaluationContext?): String?
}