package dev.openfeature.kotlin.sdk.providers.memory

import dev.openfeature.kotlin.sdk.EvaluationMetadata

/**
 * Flag representation for the in-memory provider.
 */
data class Flag<T>(
    val variants: Map<String, T>,
    val defaultVariant: String? = null,
    val contextEvaluator: ContextEvaluator<T>? = null,
    val flagMetadata: EvaluationMetadata? = null,
    val disabled: Boolean = false
) {
    init {
        if (defaultVariant != null && !variants.containsKey(defaultVariant)) {
            throw IllegalArgumentException("defaultVariant ($defaultVariant) is not present in variants map")
        }
    }

    companion object {
        fun <T> builder() = Builder<T>()
    }

    class Builder<T> {
        private val variants = mutableMapOf<String, T>()
        private var defaultVariant: String? = null
        private var contextEvaluator: ContextEvaluator<T>? = null
        private var flagMetadata: EvaluationMetadata? = null
        private var disabled: Boolean = false

        fun variant(name: String, value: T) = apply { this.variants[name] = value }
        fun variants(variants: Map<String, T>) = apply { this.variants.putAll(variants) }
        fun defaultVariant(defaultVariant: String?) = apply { this.defaultVariant = defaultVariant }
        fun contextEvaluator(contextEvaluator: ContextEvaluator<T>) = apply { this.contextEvaluator = contextEvaluator }
        fun flagMetadata(flagMetadata: EvaluationMetadata) = apply { this.flagMetadata = flagMetadata }
        fun disabled(disabled: Boolean) = apply { this.disabled = disabled }

        fun build(): Flag<T> {
            return Flag(variants.toMap(), defaultVariant, contextEvaluator, flagMetadata, disabled)
        }
    }
}