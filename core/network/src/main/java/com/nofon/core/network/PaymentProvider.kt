package com.nofon.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction stable pour l'exécution du paiement (ARCHITECTURE-2.md §9/§10). En
 * Phase 5, la seule responsabilité couverte est le déclenchement du règlement de fin
 * d'objectif — l'enregistrement d'un moyen de paiement réel (SetupIntent) reste hors
 * périmètre. Une seule implémentation existe pour l'instant, contrairement à
 * `TrackingProvider` : pas de variante Gradle tant qu'une seconde implémentation
 * réelle ne coexiste pas dans le binaire.
 */
interface PaymentProvider {
    suspend fun settleDebt(debtId: String, goalId: String, userId: String): Result<Unit>
}

@Singleton
class SimulatedPaymentProvider @Inject constructor(
    private val debtRepository: DebtRepository
) : PaymentProvider {
    override suspend fun settleDebt(debtId: String, goalId: String, userId: String): Result<Unit> =
        debtRepository.requestSettlement(debtId, goalId, userId)
}
