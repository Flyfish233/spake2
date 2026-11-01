// SPDX-License-Identifier: AGPL-3.0-only
@file:kotlin.jvm.JvmName("JvmPerformanceProbeKt")
package io.github.flyfish233.spake2.tools

import com.sun.management.ThreadMXBean
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.legacy.LegacyPasswordScalarMode
import io.github.flyfish233.spake2.legacy.LegacySpake25519
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import java.lang.management.ManagementFactory

/**
 * TEST TOOL ONLY. Uses published deterministic fixtures, never real credentials.
 * One measured operation includes BOTH parties, confirmation (RFC), and cleanup.
 * The repeated fixture entropy is deliberately unsuitable for application use.
 */
private object JvmPerformanceProbe {
    @Volatile private var blackhole = 0
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun source(bytes: ByteArray): EntropySource = EntropySource { size ->
        check(size == bytes.size)
        bytes.copyOf()
    }

    private data class RfcFixture(
        val suite: Rfc9382Suite, val w: ByteArray, val x: ByteArray, val y: ByteArray,
        val expectedKey: ByteArray,
    )

    private fun rfcExchange(f: RfcFixture) {
        val alice = Rfc9382Session(f.suite, Role.ALICE, f.w, "server".encodeToByteArray(),
            "client".encodeToByteArray(), entropy = source(f.x))
        val bob = Rfc9382Session(f.suite, Role.BOB, f.w, "server".encodeToByteArray(),
            "client".encodeToByteArray(), entropy = source(f.y))
        var ka = byteArrayOf()
        var kb = byteArrayOf()
        try {
            val pa = alice.generateMessage()
            val pb = bob.generateMessage()
            val ca = alice.processPeerMessage(pb)
            val cb = bob.processPeerMessage(pa)
            ka = alice.confirm(cb)
            kb = bob.confirm(ca)
            check(ka.contentEquals(f.expectedKey) && kb.contentEquals(f.expectedKey)) {
                "RFC known-answer mismatch in performance probe"
            }
            blackhole = blackhole xor ka.fold(1) { total, b -> total * 31 + b }
            pa.fill(0); pb.fill(0); ca.fill(0); cb.fill(0)
        } finally { ka.fill(0); kb.fill(0); alice.destroy(); bob.destroy() }
    }

    private val legacyPassword = "515109".encodeToByteArray()
    private val legacyAliceEntropy = ByteArray(64).also { it[0] = 1 }
    private val legacyBobEntropy = ByteArray(64).also { input -> for (i in 0..31) input[i] = i.toByte() }
    private val legacyExpectedKey = hex("f1e5cb026ca1dc0780d629cd1747b834bceed4b05417b80f2133a2bd0a0e8b21c1467782f8cab09ba0a20c4a785029dc95c028e20deff35812c01a3a0d8e9cb2")

    private fun legacyExchange() {
        val a = "alice".encodeToByteArray()
        val b = "bob".encodeToByteArray()
        val alice = LegacySpake25519(Role.ALICE, a, b, source(legacyAliceEntropy), LegacyPasswordScalarMode.HARDENED)
        val bob = LegacySpake25519(Role.BOB, b, a, source(legacyBobEntropy), LegacyPasswordScalarMode.HARDENED)
        var ka = byteArrayOf()
        var kb = byteArrayOf()
        try {
            val pa = alice.generateMessage(legacyPassword)
            val pb = bob.generateMessage(legacyPassword)
            ka = alice.deriveUnconfirmedKey(pb)
            kb = bob.deriveUnconfirmedKey(pa)
            check(ka.contentEquals(legacyExpectedKey) && kb.contentEquals(legacyExpectedKey)) {
                "Legacy known-answer mismatch in performance probe"
            }
            blackhole = blackhole xor ka.fold(1) { total, byte -> total * 31 + byte }
            pa.fill(0); pb.fill(0)
        } finally { ka.fill(0); kb.fill(0); alice.destroy(); bob.destroy() }
    }

    private fun median(values: LongArray): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
        else (sorted[sorted.size / 2 - 1].toDouble() + sorted[sorted.size / 2].toDouble()) / 2.0
    }

    fun run(warmups: Int, measurements: Int) {
        require(warmups >= 0 && measurements > 0)
        val runtime = Runtime.getRuntime()
        val allocationBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.let {
            if (it.isThreadAllocatedMemorySupported) {
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                it
            } else null
        }
        val thread = Thread.currentThread().id
        val memoryBean = ManagementFactory.getMemoryMXBean()
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        fun collections(): Long = collectors.sumOf { maxOf(0L, it.collectionCount) }
        fun collectionTime(): Long = collectors.sumOf { maxOf(0L, it.collectionTime) }
        println("{\"kind\":\"environment\",\"javaRuntime\":" + quote(System.getProperty("java.runtime.version")) +
            ",\"javaVm\":" + quote(System.getProperty("java.vm.name")) + ",\"os\":" + quote(System.getProperty("os.name")) +
            ",\"arch\":" + quote(System.getProperty("os.arch")) + ",\"processors\":" + runtime.availableProcessors() +
            ",\"maxHeapBytes\":" + runtime.maxMemory() + ",\"threadAllocationSupported\":" + (allocationBean != null) +
            ",\"collectors\":" + quote(collectors.joinToString(",") { it.name }) + "}")

        val p256 = RfcFixture(Rfc9382Suite.P256_SHA256,
            hex("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f"),
            hex("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729"),
            hex("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be"),
            hex("0e0672dc86f8e45565d338b0540abe69"))
        val edwards = RfcFixture(Rfc9382Suite.EDWARDS25519_SHA256,
            hex("0d5422b77a367252d4611a3ce8a1357a6a3389b28f128a42aa27280bd6b052db"),
            hex("034289404e248ad42dd8a1cca68b643c7ab9c285b85bdb83dd42ae409451d07a"),
            hex("01338b8c144114a1ce20d0d78ffb4fa9fcde438f7b472ea5a1e39895131f4e1f"),
            hex("6f0cf7cdea34153aec926dbd3183c2b7"))
        val modes = listOf<Pair<String, () -> Unit>>(
            "RFC9382-P256-confirmed-pair" to { rfcExchange(p256) },
            "RFC9382-Edwards25519-confirmed-pair" to { rfcExchange(edwards) },
            "Legacy-SPAKE25519-hardened-unconfirmed-pair" to { legacyExchange() },
        )
        for ((name, operation) in modes) {
            repeat(warmups) { operation() }
            val duration = LongArray(measurements)
            val allocated = LongArray(measurements) { -1L }
            val heapBefore = memoryBean.heapMemoryUsage.used
            val gcBefore = collections()
            val gcTimeBefore = collectionTime()
            for (i in 0 until measurements) {
                val startAllocation = allocationBean?.getThreadAllocatedBytes(thread) ?: -1L
                val start = System.nanoTime()
                operation()
                duration[i] = System.nanoTime() - start
                allocated[i] = allocationBean?.let { it.getThreadAllocatedBytes(thread) - startAllocation } ?: -1L
            }
            val heapAfter = memoryBean.heapMemoryUsage.used
            val gcCount = collections() - gcBefore
            val gcTime = collectionTime() - gcTimeBefore
            for (i in 0 until measurements) {
                println("{\"kind\":\"sample\",\"mode\":" + quote(name) + ",\"index\":$i,\"elapsedNs\":${duration[i]},\"allocatedBytes\":${allocated[i]}}")
            }
            println("{\"kind\":\"summary\",\"mode\":" + quote(name) +
                ",\"warmups\":$warmups,\"measurements\":$measurements,\"medianMs\":${median(duration) / 1e6}," +
                "\"minMs\":${duration.min().toDouble() / 1e6},\"maxMs\":${duration.max().toDouble() / 1e6}," +
                "\"medianAllocatedBytes\":${median(allocated)},\"minAllocatedBytes\":${allocated.min()},\"maxAllocatedBytes\":${allocated.max()}," +
                "\"heapUsedBeforeBytes\":$heapBefore,\"heapUsedAfterBytes\":$heapAfter,\"gcCollections\":$gcCount,\"gcTimeMs\":$gcTime}")
        }
        println("{\"kind\":\"completion\",\"verifiedExchanges\":${3 * (warmups + measurements)},\"fixtureEntropyOnly\":true}")
    }
}

fun main(args: Array<String>) {
    JvmPerformanceProbe.run(args.getOrNull(0)?.toInt() ?: 3, args.getOrNull(1)?.toInt() ?: 10)
}
