// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prevents a host-JVM test result from being reported as Android ART execution. */
class AndroidArtRuntimeTest {
    @Test fun runsInsideTheRequestedAndroidRuntime() {
        val vmName = System.getProperty("java.vm.name")
        val vmVersion = System.getProperty("java.vm.version") ?: ""
        assertEquals("Dalvik", vmName)
        assertTrue(Build.VERSION.SDK_INT >= 21, "The library requires API 21 or later")
        assertTrue(vmVersion.startsWith("2."), "Expected the Android ART runtime")
        assertTrue(Build.SUPPORTED_ABIS.isNotEmpty())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.context.packageName.endsWith(".test"))
        InstrumentationRegistry.getArguments().getString("expectedApi")?.let {
            assertEquals(it.toInt(), Build.VERSION.SDK_INT, "Unexpected emulator API level")
        }
        val evidence = "SPAKE2_ART_RUNTIME sdk=" + Build.VERSION.SDK_INT +
            " vm=" + vmName + " version=" + vmVersion +
            " abis=" + Build.SUPPORTED_ABIS.joinToString(",")
        Log.i("SPAKE2_ART", evidence)
        println(evidence)
    }
}
