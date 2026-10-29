package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lu depuis `debts` — une ligne par objectif payant. Voir `0003_money.sql`. */
@Serializable
data class Debt(
    val id: String,
    @SerialName("goal_id") val goalId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("amount_cents") val amountCents: Long,
    val status: String, // PENDING / SETTLED / UNPAID_DEBT
    @SerialName("settled_at") val settledAt: String? = null
)

/** Lu depuis `ledger_entries` — append-only, jamais écrit directement par le client. */
@Serializable
data class LedgerEntry(
    val id: String,
    @SerialName("goal_id") val goalId: String,
    @SerialName("debt_id") val debtId: String? = null,
    @SerialName("local_date") val localDate: String? = null,
    @SerialName("entry_type") val entryType: String, // DEBT_ACCRUED / SETTLEMENT
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("charity_cents") val charityCents: Long? = null,
    @SerialName("nofon_cents") val nofonCents: Long? = null,
    val simulated: Boolean
)

/** Seul insert client autorisé côté argent : déclenche `settle_debt()` (SECURITY DEFINER). */
@Serializable
data class NewDebtSettlementRequest(
    @SerialName("debt_id") val debtId: String,
    @SerialName("goal_id") val goalId: String,
    @SerialName("user_id") val userId: String
)
