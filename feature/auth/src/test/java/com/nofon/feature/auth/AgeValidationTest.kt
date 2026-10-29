package com.nofon.feature.auth

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeValidationTest {

    private val today = LocalDate.of(2026, 8, 22)

    @Test
    fun `exactement 18 ans aujourd hui est accepte`() {
        assertTrue(isAtLeast18(LocalDate.of(2008, 8, 22), today))
    }

    @Test
    fun `18 ans moins un jour est refuse`() {
        assertFalse(isAtLeast18(LocalDate.of(2008, 8, 23), today))
    }

    @Test
    fun `17 ans est refuse`() {
        assertFalse(isAtLeast18(LocalDate.of(2009, 1, 1), today))
    }

    @Test
    fun `bien au-dela de 18 ans est accepte`() {
        assertTrue(isAtLeast18(LocalDate.of(1990, 1, 1), today))
    }

    @Test
    fun `date de naissance dans le futur est refusee`() {
        assertFalse(isAtLeast18(LocalDate.of(2027, 1, 1), today))
    }
}
