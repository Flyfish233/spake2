// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

/**
 * Supplies fresh, independent CSPRNG bytes. The returned array must contain exactly
 * [size] bytes and ownership is transferred to the library, which may erase it.
 * Never implement this with kotlin.random.Random. Throw on failure; do not return
 * predictable fallback bytes. The library cannot measure or certify entropy quality.
 */
public fun interface EntropySource {
    public fun nextBytes(size: Int): ByteArray
}

/** Explicit protocol role; both endpoints must agree on the ordering of identities. */
public enum class Role { ALICE, BOB }

/**
 * A terminal protocol, input, state or entropy error. Library-generated messages
 * omit secret bytes. Caller-provided causes are preserved without sanitization.
 */
public class Spake2Exception(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
