package dev.openfeature.kotlin.sdk.isolated

/**
 * Marks APIs related to isolated [dev.openfeature.kotlin.sdk.OpenFeatureAPIInstance]s as
 * experimental. The shape, semantics, and existence of these APIs may change in future releases.
 *
 * Consumers must explicitly opt-in via `@OptIn(ExperimentalIsolatedApi::class)` or by propagating
 * the annotation.
 *
 * @see <a href="https://openfeature.dev/specification/sections/flag-evaluation#18-isolated-api-instances">Spec 1.8</a>
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Isolated OpenFeature API instances are experimental and subject to change."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS
)
annotation class ExperimentalIsolatedApi