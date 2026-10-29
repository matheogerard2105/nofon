package com.nofon.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.Avatar
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonConfirmDialog
import com.nofon.core.designsystem.NofonPrimaryButton
import com.nofon.core.designsystem.NofonSecondaryButton
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import com.nofon.core.designsystem.capitalizedHandle
import java.time.Duration
import java.time.Instant

@Composable
fun GroupScreen(onOpenProfile: (String) -> Unit = {}, viewModel: GroupViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        actionError?.let {
            NofonCard { Text(it, color = colors.error, style = NofonTextStyles.body) }
        }

        when (val current = state) {
            GroupUiState.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Chargement…", style = NofonTextStyles.body, color = colors.onSurfaceVariant)
            }
            GroupUiState.Locked -> LockedContent()
            GroupUiState.NoMembership -> NoMembershipContent(viewModel)
            is GroupUiState.PendingApproval -> PendingApprovalContent(current)
            is GroupUiState.Dashboard -> DashboardContent(current, viewModel, onOpenProfile)
        }
    }
}

@Composable
private fun LockedContent() {
    val colors = NofonTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = colors.onSurfaceTertiary, modifier = Modifier.size(30.dp))
        }
        Text(
            "Vous devez avoir un objectif payant en cours pour accéder à cette fonctionnalité.",
            style = NofonTextStyles.body,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NoMembershipContent(viewModel: GroupViewModel) {
    var code by remember { mutableStateOf("") }
    val colors = NofonTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Groupe", style = NofonTextStyles.title, color = colors.onSurface)
        Text(
            "La difficulté collective augmente avec la taille du groupe : le résultat du " +
                "jour n'est réussi que si TOUS les membres actifs réussissent le leur " +
                "(PRODUCT_SPEC-2.md §12.2). Ce n'est pas un bug, c'est la règle.",
            style = NofonTextStyles.body,
            color = colors.onSurfaceVariant
        )

        NofonCard {
            SectionLabel("Créer un groupe")
            NofonPrimaryButton("Créer", onClick = viewModel::createGroup)
        }

        NofonCard {
            SectionLabel("Rejoindre avec un code")
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                placeholder = { Text("Code du groupe") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            NofonPrimaryButton(
                "Envoyer une demande",
                onClick = { viewModel.joinByCode(code) },
                enabled = code.isNotBlank()
            )
        }

        EndOfListNote("Fin de l'écran.")
    }
}

@Composable
private fun PendingApprovalContent(state: GroupUiState.PendingApproval) {
    val colors = NofonTheme.colors
    NofonCard {
        SectionLabel("Demande envoyée")
        Text("Code du groupe : ${state.group.code}", style = NofonTextStyles.smallMono, color = colors.onSurface)
        Text(
            "En attente de l'acceptation du créateur du groupe.",
            style = NofonTextStyles.small,
            color = colors.onSurfaceVariant
        )
    }
}

private data class PendingConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean,
    val action: () -> Unit
)

@Composable
private fun DashboardContent(state: GroupUiState.Dashboard, viewModel: GroupViewModel, onOpenProfile: (String) -> Unit) {
    val colors = NofonTheme.colors
    val context = LocalContext.current
    var showRename by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupAvatar(
                avatarUrl = state.group.avatarUrl,
                editable = state.isCreator,
                onImagePicked = { uri -> viewModel.changeGroupAvatar(context, uri) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    state.group.name,
                    style = NofonTextStyles.title,
                    color = colors.onSurface,
                    modifier = if (state.isCreator) Modifier.clickable { showRename = true } else Modifier
                )
                Text("Code : ${state.group.code}", style = NofonTextStyles.smallMono, color = colors.onSurfaceVariant)
            }
        }

        if (showRename) {
            RenameGroupDialog(
                initialName = state.group.name,
                onDismiss = { showRename = false },
                onConfirm = { name -> viewModel.renameGroup(name); showRename = false }
            )
        }

        if (state.pendingRequests.isNotEmpty() && state.isCreator) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Demandes en attente")
                state.pendingRequests.forEach { request ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(url = request.avatarUrl, size = 28.dp)
                            Text(request.handle.capitalizedHandle(), style = NofonTextStyles.body, color = colors.onSurface)
                        }
                        Row {
                            TextButton(onClick = {
                                pendingConfirm = PendingConfirm(
                                    title = "Accepter la demande ?",
                                    message = "${request.handle.capitalizedHandle()} rejoindra le groupe immédiatement.",
                                    confirmLabel = "Accepter",
                                    destructive = false,
                                    action = { viewModel.acceptRequest(state.group.id, request.userId) }
                                )
                            }) {
                                Text("Accepter", color = colors.accent)
                            }
                            TextButton(onClick = {
                                pendingConfirm = PendingConfirm(
                                    title = "Refuser la demande ?",
                                    message = "${request.handle.capitalizedHandle()} ne rejoindra pas le groupe.",
                                    confirmLabel = "Refuser",
                                    destructive = true,
                                    action = { viewModel.rejectRequest(state.group.id, request.userId) }
                                )
                            }) {
                                Text("Refuser", color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Membres (${state.members.size}/9)")
            MemberGrid(
                members = state.members,
                canRemove = state.isCreator,
                myUserId = state.myMembership.userId,
                onOpenProfile = onOpenProfile,
                onRemove = { member ->
                    pendingConfirm = PendingConfirm(
                        title = "Exclure ${member.handle.capitalizedHandle()} ?",
                        message = "${member.handle.capitalizedHandle()} quittera le groupe dans 24h.",
                        confirmLabel = "Exclure",
                        destructive = true,
                        action = { viewModel.removeMember(member.userId) }
                    )
                }
            )
        }

        GroupProgressSection(
            series = state.progressSeries,
            members = state.members,
            todayPercentByUserId = state.todayPercentByUserId
        )

        LeaveOrDissolveSection(
            state = state,
            onLeave = {
                pendingConfirm = PendingConfirm(
                    title = "Quitter le groupe ?",
                    message = "Tu quitteras le groupe dans 24h.",
                    confirmLabel = "Quitter",
                    destructive = true,
                    action = viewModel::leaveGroup
                )
            },
            onDissolve = {
                pendingConfirm = PendingConfirm(
                    title = "Dissoudre le groupe ?",
                    message = "Le groupe sera dissous dans 24h. Vos objectifs individuels ne sont pas affectés.",
                    confirmLabel = "Dissoudre",
                    destructive = true,
                    action = viewModel::dissolveGroup
                )
            }
        )

        EndOfListNote("Fin de l'écran.")
    }

    pendingConfirm?.let { confirm ->
        NofonConfirmDialog(
            title = confirm.title,
            message = confirm.message,
            confirmLabel = confirm.confirmLabel,
            destructive = confirm.destructive,
            onConfirm = { confirm.action(); pendingConfirm = null },
            onDismiss = { pendingConfirm = null }
        )
    }
}

@Composable
private fun RenameGroupDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val colors = NofonTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Renommer le groupe", style = NofonTextStyles.title, color = colors.onSurface) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = NofonTextStyles.body,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.accent
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Enregistrer", style = NofonTextStyles.bodyEmphasis, color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = colors.onSurfaceVariant) }
        }
    )
}

/** 3 cadres max par ligne, dernière ligne incomplète centrée (demande explicite). */
@Composable
private fun MemberGrid(
    members: List<MemberCardUiState>,
    canRemove: Boolean,
    myUserId: String,
    onOpenProfile: (String) -> Unit,
    onRemove: (MemberCardUiState) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = 10.dp
        val cardWidth = (maxWidth - spacing * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            members.chunked(3).forEach { row ->
                val arrangement = if (row.size == 3) Arrangement.spacedBy(spacing) else Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                    row.forEach { member ->
                        MemberCard(
                            member = member,
                            modifier = Modifier.width(cardWidth),
                            canRemove = canRemove && member.userId != myUserId && member.status == "ACTIVE",
                            onOpenProfile = { onOpenProfile(member.userId) },
                            onRemove = { onRemove(member) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: MemberCardUiState,
    modifier: Modifier,
    canRemove: Boolean,
    onOpenProfile: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = NofonTheme.colors
    Box(
        modifier
            .aspectRatio(1f)
            .border(2.dp, member.color, RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenProfile)
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Avatar(url = member.avatarUrl, size = 34.dp)
            Text(
                member.handle.capitalizedHandle(),
                style = NofonTextStyles.small,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
            ObjectiveBadge(member.constraintType, member.timeLimitSeconds, member.opensLimit)
            if (member.status == "LEAVING") {
                Text("départ en cours", style = NofonTextStyles.small, color = colors.error, textAlign = TextAlign.Center)
            }
        }
        if (canRemove) {
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(26.dp)) {
                Icon(Icons.Default.RemoveCircle, contentDescription = "Exclure", tint = colors.error, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Logo temps + durée et/ou logo déverrouillage + nombre, selon la contrainte de l'objectif (demande explicite). */
@Composable
private fun ObjectiveBadge(constraintType: String?, timeLimitSeconds: Int?, opensLimit: Int?) {
    val colors = NofonTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp)
    ) {
        if (constraintType == "TIME" || constraintType == "BOTH") {
            timeLimitSeconds?.let {
                Icon(Icons.Default.AccessTime, contentDescription = null, tint = colors.onSurfaceTertiary, modifier = Modifier.size(11.dp))
                Text("${it / 60}", style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
            }
        }
        if (constraintType == "OPENS" || constraintType == "BOTH") {
            opensLimit?.let {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = colors.onSurfaceTertiary, modifier = Modifier.size(11.dp))
                Text("$it", style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
            }
        }
    }
}

@Composable
private fun LeaveOrDissolveSection(state: GroupUiState.Dashboard, onLeave: () -> Unit, onDissolve: () -> Unit) {
    if (state.isCreator) {
        val effectiveAt = state.group.dissolveEffectiveAt
        if (effectiveAt != null) {
            GreyCountdownLabel(countdownLabel("Groupe dissous", effectiveAt))
        } else {
            NofonSecondaryButton("Dissoudre le groupe", onClick = onDissolve)
        }
    } else if (state.myMembership.status == "ACTIVE") {
        NofonSecondaryButton("Quitter le groupe", onClick = onLeave)
    } else if (state.myMembership.status == "LEAVING") {
        GreyCountdownLabel(countdownLabel("Vous quitterez le groupe", state.myMembership.leftEffectiveAt))
    }
}

@Composable
private fun GreyCountdownLabel(text: String) {
    val colors = NofonTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(9.dp))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = NofonTextStyles.bodyEmphasis, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun countdownLabel(prefix: String, effectiveAt: String?): String {
    if (effectiveAt == null) return "$prefix — en cours."
    val remaining = Duration.between(Instant.now(), Instant.parse(effectiveAt))
    if (remaining.isNegative) return "$prefix — effectif."
    val hours = remaining.toHours()
    val minutes = remaining.toMinutesPart()
    return "$prefix dans %d h %02d min".format(hours, minutes)
}
