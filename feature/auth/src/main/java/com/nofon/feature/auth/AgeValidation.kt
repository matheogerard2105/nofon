package com.nofon.feature.auth

import java.time.LocalDate
import java.time.Period

/**
 * PRODUCT_SPEC-2.md §1 : 18 ans minimum sur l'intégralité du produit, dès
 * l'inscription. Fonction pure et testable — la même règle est aussi imposée en
 * base (CHECK SQL sur `users.birth_date`) pour ne jamais dépendre uniquement du client.
 */
fun isAtLeast18(birthDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    Period.between(birthDate, today).years >= 18
