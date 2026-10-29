package com.nofon.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme

/**
 * Récapitulatif de fin d'objectif (PRODUCT_SPEC-2.md §13). Pas de fermeture accidentelle
 * (`onDismissRequest = {}`) : un objectif payant qui se termine mérite d'être lu jusqu'au
 * bout, l'utilisateur doit explicitement l'acquitter.
 */
@Composable
fun GoalRecapDialog(state: GoalRecapUiState, onAcknowledge: () -> Unit) {
    val goal = state.goal
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Objectif terminé") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Durée : ${goal.durationDays} jours (${goal.startDate} → ${goal.endDate})")
                Text("Journées réussies : ${state.successDays} · ratées : ${state.failedDays} · sans données : ${state.noDataDays}")
                Text("Taux de réussite : ${state.successRatePercent} %")
                Text("Meilleure série : ${state.bestStreakDays} jours")
                Text("Caution sécurisée : ${fmtCents(state.securedCents)}")

                val debt = state.debt
                when {
                    debt == null || debt.status == "SETTLED" -> if ((debt?.amountCents ?: 0) > 0) {
                        Text("Montant perdu : ${fmtCents(debt!!.amountCents)}", color = NofonTheme.colors.error)
                        state.settlementEntry?.let { entry ->
                            Text("Prélevé (simulé) : ${fmtCents(entry.amountCents)}")
                            Text(
                                "dont association ${fmtCents(entry.charityCents ?: 0)} · Nofon ${fmtCents(entry.nofonCents ?: 0)}",
                                style = NofonTextStyles.small
                            )
                        }
                    } else {
                        Text("Aucune dette : caution intégralement sécurisée.")
                    }
                    debt.status == "PENDING" -> Text(
                        "Prélèvement en cours de traitement…",
                        style = NofonTextStyles.small
                    )
                    debt.status == "UNPAID_DEBT" -> Text(
                        "Le prélèvement final n'a pas abouti — montant dû : ${fmtCents(debt.amountCents)}",
                        color = NofonTheme.colors.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text("Compris") }
        }
    )
}
