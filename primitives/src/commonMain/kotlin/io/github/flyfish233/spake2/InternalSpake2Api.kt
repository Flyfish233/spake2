// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

/**
 * Implementation-only bridge shared by the RFC and Legacy artifacts.
 * No compatibility or validation guarantees are offered for direct use.
 * Applications must use the public protocol classes instead of raw arithmetic.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Internal cryptographic implementation API; use a protocol API")
@Retention(AnnotationRetention.BINARY)
public annotation class InternalSpake2Api
