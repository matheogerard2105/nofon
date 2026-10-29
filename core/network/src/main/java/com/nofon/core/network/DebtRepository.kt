package com.nofon.core.network

/**
 * Registre financier (`debts`/`ledger_entries`, `0003_money.sql`). Le client n'écrit
 * jamais directement dans ces deux tables — réservées aux triggers SECURITY DEFINER.
 * La seule écriture cliente possible est une insertion dans `debt_settlement_requests`,
 * qui déclenche le règlement simulé côté serveur (ARCHITECTURE-2.md §3/§8/§10).
 */
interface DebtRepository {
    suspend fun debtForGoal(goalId: String): Result<Debt?>
    suspend fun ledgerEntriesForGoal(goalId: String): Result<List<LedgerEntry>>

    /** Idempotent côté serveur (`unique(debt_id)`) : une seconde tentative sur une dette déjà réglée/en cours échoue silencieusement. */
    suspend fun requestSettlement(debtId: String, goalId: String, userId: String): Result<Unit>
}
