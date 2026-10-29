package com.nofon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonPrimaryButton
import com.nofon.core.designsystem.NofonSecondaryButton
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import java.time.ZoneId

private val DURATION_PRESETS = listOf(7, 14, 30, 60, 90)

/**
 * Formulaire de création d'objectif (PRODUCT_SPEC-2.md §2). La confirmation finale se
 * fait par [GoalConfirmationDialog], pas dans cet écran — pour laisser un dernier point
 * de sortie avant l'irréversibilité de §2.3.
 */
@Composable
fun GoalCreationScreen(
    onDone: () -> Unit,
    viewModel: GoalCreationViewModel = hiltViewModel()
) {
    val form by viewModel.state.collectAsStateWithLifecycle()
    var showAppsPicker by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var customDurationText by remember { mutableStateOf("") }
    val colors = NofonTheme.colors

    LaunchedEffect(form.createdGoalId) {
        if (form.createdGoalId != null) {
            viewModel.consumeCreatedGoal()
            onDone()
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.divider,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.onSurfaceVariant,
        cursorColor = colors.accent
    )
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = colors.surface,
        labelColor = colors.onSurfaceVariant,
        selectedContainerColor = colors.accent.copy(alpha = 0.16f),
        selectedLabelColor = colors.accent
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row {
            TextButton(onClick = onDone) { Text("Annuler", color = colors.onSurfaceVariant) }
        }

        Text("Nouvel objectif", style = NofonTextStyles.title, color = colors.onSurface)

        SectionLabel("Type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.kind == GoalKind.FREE,
                onClick = { viewModel.updateKind(GoalKind.FREE) },
                label = { Text("Gratuit") },
                colors = chipColors
            )
            FilterChip(
                selected = form.kind == GoalKind.STAKED,
                onClick = { viewModel.updateKind(GoalKind.STAKED) },
                label = { Text("Payant") },
                colors = chipColors
            )
        }

        SectionLabel("Durée")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DURATION_PRESETS.forEach { preset ->
                FilterChip(
                    selected = form.durationDays == preset,
                    onClick = { viewModel.updateDuration(preset); customDurationText = "" },
                    label = { Text("$preset j") },
                    colors = chipColors
                )
            }
        }
        OutlinedTextField(
            value = customDurationText,
            onValueChange = { text ->
                customDurationText = text
                text.toIntOrNull()?.let { viewModel.updateDuration(it) }
            },
            label = { Text("Durée personnalisée (3 à 365 jours)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        SectionLabel("Contrainte")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.constraintType == ConstraintKind.TIME,
                onClick = { viewModel.updateConstraintType(ConstraintKind.TIME) },
                label = { Text("Temps") },
                colors = chipColors
            )
            FilterChip(
                selected = form.constraintType == ConstraintKind.OPENS,
                onClick = { viewModel.updateConstraintType(ConstraintKind.OPENS) },
                label = { Text("Ouvertures") },
                colors = chipColors
            )
            FilterChip(
                selected = form.constraintType == ConstraintKind.BOTH,
                onClick = { viewModel.updateConstraintType(ConstraintKind.BOTH) },
                label = { Text("Les deux") },
                colors = chipColors
            )
        }

        if (form.constraintType != ConstraintKind.OPENS) {
            OutlinedTextField(
                value = form.timeLimitMinutes.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let(viewModel::updateTimeLimitMinutes) },
                label = { Text("Limite de temps d'écran par jour (15 à 300 min)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (form.constraintType != ConstraintKind.TIME) {
            OutlinedTextField(
                value = form.opensLimit.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let(viewModel::updateOpensLimit) },
                label = { Text("Limite de déverrouillages par jour (5 à 100)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionLabel("Applications exception")
        NofonSecondaryButton(
            if (form.excludedPackages.isEmpty()) "Aucune sélectionnée" else "${form.excludedPackages.size} sélectionnée(s)",
            onClick = { showAppsPicker = true }
        )

        if (form.kind == GoalKind.STAKED) {
            SectionLabel("Montant engagé")
            OutlinedTextField(
                value = form.stakeEuros.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let(viewModel::updateStakeEuros) },
                label = { Text("Montant total en euros (minimum ${form.minStakeEuros} €)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            if (form.belowMinimumStake) {
                Text(
                    "Minimum : ${form.minStakeEuros} € (1 €/jour).",
                    color = colors.error,
                    style = NofonTextStyles.small
                )
            }
            if (form.exceedsDailyCap) {
                Text(
                    "Part journalière trop élevée (plafond : ${form.maxDailyStakeCents / 100.0} €/jour).",
                    color = colors.error,
                    style = NofonTextStyles.small
                )
            }
            if (form.exceedsTotalCap) {
                Text(
                    "Montant total trop élevé (plafond : ${form.maxTotalStakeCents / 100.0} €).",
                    color = colors.error,
                    style = NofonTextStyles.small
                )
            }
        }

        val errorMessage = form.error
        if (errorMessage != null) {
            NofonCard {
                Text(errorMessage, color = colors.error, style = NofonTextStyles.body)
            }
        }

        NofonPrimaryButton(
            "Continuer",
            onClick = { showConfirmation = true },
            enabled = form.isValid && !form.isSubmitting
        )
    }

    if (showAppsPicker) {
        ExcludedAppsPickerDialog(
            apps = viewModel.availableApps(),
            initiallySelected = form.excludedPackages,
            onConfirm = { selected ->
                viewModel.updateExcludedPackages(selected)
                showAppsPicker = false
            },
            onDismiss = { showAppsPicker = false }
        )
    }

    if (showConfirmation) {
        val labels = viewModel.availableApps()
            .filter { form.excludedPackages.contains(it.packageName) }
            .map { it.label }
        GoalConfirmationDialog(
            form = form,
            excludedAppLabels = labels,
            timezoneId = ZoneId.systemDefault().id,
            onConfirm = { viewModel.submit() },
            onDismiss = { showConfirmation = false }
        )
    }
}
