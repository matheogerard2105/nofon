package com.nofon.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseDebtRepository @Inject constructor(
    private val client: SupabaseClient
) : DebtRepository {

    override suspend fun debtForGoal(goalId: String): Result<Debt?> = runCatching {
        client.from("debts").select {
            filter { eq("goal_id", goalId) }
        }.decodeList<Debt>().firstOrNull()
    }

    override suspend fun ledgerEntriesForGoal(goalId: String): Result<List<LedgerEntry>> = runCatching {
        client.from("ledger_entries").select {
            filter { eq("goal_id", goalId) }
        }.decodeList<LedgerEntry>()
    }

    override suspend fun requestSettlement(debtId: String, goalId: String, userId: String): Result<Unit> = runCatching {
        client.from("debt_settlement_requests").insert(
            NewDebtSettlementRequest(debtId = debtId, goalId = goalId, userId = userId)
        )
        Unit
    }
}
