package com.example.janggiai.engine

import com.example.janggiai.engine.fsf.FairyStockfishBuildInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** The Kotlin constants shown in the About screen must match the pin file used by the fetch script. */
class FairyStockfishBuildInfoTest {
    private fun props(): Properties {
        val candidates = listOf(File("fsf.properties"), File("engine/fsf.properties"), File("../engine/fsf.properties"))
        val f = candidates.firstOrNull { it.exists() } ?: throw AssertionError("fsf.properties not found from ${File(".").absolutePath}")
        return Properties().apply { f.inputStream().use { load(it) } }
    }

    @Test fun kotlinConstantsMatchPinFile() {
        val p = props()
        assertEquals(p.getProperty("FSF_VERSION"), FairyStockfishBuildInfo.VERSION)
        assertEquals(p.getProperty("FSF_COMMIT"), FairyStockfishBuildInfo.COMMIT)
        assertEquals(p.getProperty("FSF_TARBALL_URL"), FairyStockfishBuildInfo.TARBALL_URL)
        assertEquals(p.getProperty("FSF_TARBALL_SHA256"), FairyStockfishBuildInfo.TARBALL_SHA256)
        assertEquals(p.getProperty("FSF_LICENSE"), FairyStockfishBuildInfo.LICENSE_ID)
        assertTrue(FairyStockfishBuildInfo.COMMIT.matches(Regex("[0-9a-f]{40}")))
        assertTrue(FairyStockfishBuildInfo.TARBALL_SHA256.matches(Regex("[0-9a-f]{64}")))
    }
}
