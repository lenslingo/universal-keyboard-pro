package com.ukpro.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the dependency-free CoreTests suite under Gradle (`./gradlew :core:test`). */
class CoreTestsJUnit {
    @Test
    fun allCoreTestsPass() {
        val vectors = File(System.getProperty("ukpro.vectors") ?: "../../shared/test-vectors")
        assertEquals("failing core tests (see stdout)", 0, CoreTests.runAll(vectors))
    }
}
