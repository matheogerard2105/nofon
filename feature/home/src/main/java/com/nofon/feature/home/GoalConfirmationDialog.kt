package com.nofon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonPrimaryButton
import com.nofon.core.designsystem.NofonSecondaryButton
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import java.time.LocalDate

/**
 * Récapitulatif complet avant tout envoi. Un objectif lancé ne peut plus être ni
 * modifié ni abandonné (PRODUCT_SPEC-2.md §2.3) : ce dialogue est le dernier moment où
 * l'utilisateur peut encore changer d'avis, donc il montre tout — y compris ce qui ne
 * saute pas aux yeux dans le formulaire (fuseau retenu, périmètre mono-appareil §14).
 */
@Composable
fun GoalConfirmationDialog(
    form: GoalCreationFormState,
    excludedAppLabels: List<String>,
    timezoneId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NofonTheme.colors
    val startDate = LocalDate.now()
    val endDate = startDate.plusDays(form.durationDays.toLong())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .background(colors.surface, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Dernière étape")
                Text("Lancer cet objectif ?", style = NofonTextStyles.title, color = colors.onSurface)
            }

            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (form.kind == GoalKind.STAKED) {
                    NofonCard {
                        SectionLabel("Montant engagé")
                        Text("${form.stakeEuros} €", style = NofonTextStyles.amountLarge(30.sp), color = colors.accent)
                        Text(
                            "~${form.dailyStakeCents / 100.0} € / jour (arrondi sur les premiers jours)",
                            style = NofonTextStyles.small,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                NofonCard {
                    SummaryRow("Type", if (form.kind == GoalKind.STAKED) "Payant" else "Gratuit")
                    SummaryRow("Durée", "${form.durationDays} jours")
                    SummaryRow("Période", "$startDate → $endDate")
                    SummaryRow("Fuseau (figé)", timezoneId)
                    when (form.constraintType) {
                        ConstraintKind.TIME -> SummaryRow("Limite", "${form.timeLimitMinutes} min d'écran / jour")
                        ConstraintKind.OPENS -> SummaryRow("Limite", "${form.opensLimit} déverrouillages / jour")
                        ConstraintKind.BOTH -> SummaryRow(
                            "Limites",
                            "${form.timeLimitMinutes} min d'écran ET ${form.opensLimit} déverrouillages / jour"
                        )
                    }
                    SummaryRow(
                        "Apps exception",
                        if (excludedAppLabels.isEmpty()) "Aucune" else excludedAppLabels.joinToString(", ")
                    )
                }

                Text(
                    "Une fois lancé, cet objectif — dates, limites, applications exception, montant — " +
                        "ne pourra plus être ni modifié ni abandonné.",
                    style = NofonTextStyles.body,
                    color = colors.onSurfaceVariant
                )
                Text(
                    "Nofon ne mesure qu'un seul appareil : un autre téléphone, une tablette ou un " +
                        "ordinateur ne comptent pas dans cet objectif.",
                    style = NofonTextStyles.small,
                    color = colors.onSurfaceTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NofonSecondaryButton(
                    text = "Retour",
                    onClick = onDismiss,
                    enabled = !form.isSubmitting,
                    modifier = Modifier.weight(1f)
                )
                NofonPrimaryButton(
                    text = if (form.isSubmitting) "Envoi..." else "Lancer",
                    onClick = onConfirm,
                    enabled = !form.isSubmitting,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val colors = NofonTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = NofonTextStyles.body, color = colors.onSurfaceVariant)
        Text(
            value,
            style = NofonTextStyles.bodyEmphasis,
            color = colors.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
