package com.nofon.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.Avatar
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.EngagementChip
import com.nofon.core.designsystem.ExploitRarityTier
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.capitalizedHandle
import com.nofon.core.designsystem.relativeTimeLabel
import com.nofon.core.network.FeedEvent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SocialScreen(
    onOpenProfile: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    viewModel: SocialViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = NofonTheme.colors
    val items by viewModel.items.collectAsStateWithLifecycle()
    val unread by viewModel.unreadNotifications.collectAsStateWithLifecycle()
    var openCommentsFor by remember { mutableStateOf<FeedEvent?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Fil", style = NofonTextStyles.title, color = colors.onSurface)
            Row {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Rechercher", tint = colors.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = colors.onSurfaceVariant)
                    }
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.accent)
                        )
                    }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.event.id }) { item ->
                FeedItemCard(
                    item = item,
                    onOpenProfile = { onOpenProfile(item.event.userId) },
                    onToggleReaction = { viewModel.toggleReaction(item.event.id) },
                    onOpenComments = { openCommentsFor = item.event }
                )
            }
            item {
                EndOfListNote(
                    if (items.isEmpty()) "Personne à afficher pour l'instant — suis des comptes pour voir leurs événements ici."
                    else "Fin du fil. Rien de plus à consulter."
                )
            }
        }
    }

    openCommentsFor?.let { event ->
        CommentsDialog(
            feedEventId = event.id,
            feedEventAuthorId = event.userId,
            onDismiss = { openCommentsFor = null; viewModel.refresh() }
        )
    }
}

/**
 * Publication automatique (exploit débloqué / objectif terminé) — demande explicite :
 * plus grande et plus riche qu'une simple ligne de texte. Un exploit affiche en plus sa
 * rareté (recalculée en direct, TODO-33), son taux d'obtention et sa description, avec
 * un cadre coloré par palier — même langage visuel que la grille `ExploitsScreen`.
 */
@Composable
private fun FeedItemCard(
    item: FeedItemUiState,
    onOpenProfile: () -> Unit,
    onToggleReaction: () -> Unit,
    onOpenComments: () -> Unit
) {
    val colors = NofonTheme.colors
    val detail = item.exploitDetail
    val frameColor = when {
        detail != null -> detail.tier?.color ?: colors.divider
        item.event.eventType == "GOAL_STARTED" -> colors.info
        else -> null
    }
    val frameModifier = frameColor?.let { Modifier.border(2.5.dp, it, RoundedCornerShape(16.dp)) } ?: Modifier

    NofonCard(modifier = frameModifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable(onClick = onOpenProfile)
            ) {
                Avatar(url = item.authorAvatarUrl, size = 24.dp)
                Text(item.authorHandle.capitalizedHandle(), style = NofonTextStyles.small, color = colors.onSurfaceVariant)
            }
            Text(relativeTimeLabel(item.event.createdAt), style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
        }

        when {
            detail != null -> ExploitPostBody(detail)
            item.event.eventType == "GOAL_STARTED" -> GoalStartedPostBody(item.event)
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                Text(feedEventLabel(item.event), style = NofonTextStyles.bodyEmphasis, color = colors.onSurface)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EngagementChip(
                icon = if (item.didIReact) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                count = item.reactionCount,
                contentDescription = "Réagir",
                active = item.didIReact,
                onClick = onToggleReaction
            )
            EngagementChip(
                icon = Icons.Default.ChatBubbleOutline,
                count = item.commentCount,
                contentDescription = "Commentaires",
                onClick = onOpenComments
            )
        }
    }
}

/** Corps enrichi d'un post `EXPLOIT_UNLOCKED`. Trophée/cadenas selon CELUI QUI REGARDE, pas l'auteur. */
@Composable
private fun ExploitPostBody(detail: ExploitFeedDetail) {
    val colors = NofonTheme.colors
    val tierColor = detail.tier?.color ?: colors.onSurfaceTertiary
    // Même règle "secret" que la grille des exploits : condition masquée tant que le
    // palier est Inconnu ET que celui qui regarde ne l'a pas lui-même débloqué.
    val hideDescription = detail.tier == ExploitRarityTier.INCONNU && !detail.viewerUnlocked

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (detail.viewerUnlocked) Icons.Default.EmojiEvents else Icons.Default.Lock,
            contentDescription = if (detail.viewerUnlocked) "Débloqué" else "Verrouillé",
            tint = if (detail.viewerUnlocked) tierColor else colors.onSurfaceTertiary,
            modifier = Modifier.size(34.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nouvel exploit débloqué", style = NofonTextStyles.label, color = colors.onSurfaceVariant)
                detail.tier?.let { tier -> Text(tier.label, style = NofonTextStyles.label, color = tier.color) }
            }
            Text(detail.name, style = NofonTextStyles.bodyEmphasis, color = colors.onSurface)
            Text(
                if (hideDescription) "???" else detail.description,
                style = NofonTextStyles.small,
                color = colors.onSurfaceTertiary
            )
        }
        Text(
            detail.percentage?.let { "%.1f".format(it) + " %" } ?: "Nouveau",
            style = NofonTextStyles.small,
            color = colors.onSurfaceTertiary
        )
    }
}

/**
 * Corps enrichi d'un post `GOAL_STARTED` — tous les détails PUBLICS de l'objectif
 * (demande explicite). `time_limit_seconds`/`opens_limit` absents pour un post créé
 * avant l'ajout de ces champs au trigger serveur : ligne de limite simplement omise,
 * jamais une valeur inventée. Le montant engagé (`max_committed_cents`) ne fait
 * volontairement PAS partie de `content` — information financière, jamais publique
 * (PRODUCT_SPEC-2.md §10.1), même ici.
 */
@Composable
private fun GoalStartedPostBody(event: FeedEvent) {
    val colors = NofonTheme.colors
    val obj = event.content.jsonObject
    val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
    val constraintType = obj["constraint_type"]?.jsonPrimitive?.contentOrNull
    val durationDays = obj["duration_days"]?.jsonPrimitive?.intOrNull
    val timeLimitSeconds = obj["time_limit_seconds"]?.jsonPrimitive?.intOrNull
    val opensLimit = obj["opens_limit"]?.jsonPrimitive?.intOrNull

    val limitLabel = when (constraintType) {
        "TIME" -> timeLimitSeconds?.let { "${it / 60} min/jour" }
        "OPENS" -> opensLimit?.let { "$it déverr./jour" }
        "BOTH" -> {
            val time = timeLimitSeconds?.let { "${it / 60} min" }
            val opens = opensLimit?.let { "$it déverr." }
            listOfNotNull(time, opens).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.plus("/jour")
        }
        else -> null
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Flag, contentDescription = null, tint = colors.info, modifier = Modifier.size(34.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Nouvel objectif lancé", style = NofonTextStyles.label, color = colors.onSurfaceVariant)
            Text(
                if (kind == "STAKED") "Objectif payant" else "Objectif gratuit",
                style = NofonTextStyles.bodyEmphasis,
                color = colors.onSurface
            )
            Text(
                listOfNotNull(durationDays?.let { "$it jours" }, limitLabel).joinToString(" · "),
                style = NofonTextStyles.small,
                color = colors.onSurfaceTertiary
            )
        }
    }
}

/** `content` figé côté serveur (voir `FeedEvent`) — juste mis en forme ici, jamais recalculé. */
private fun feedEventLabel(event: FeedEvent): String {
    val obj = event.content.jsonObject
    return when (event.eventType) {
        "EXPLOIT_UNLOCKED" -> {
            val name = obj["exploit_name"]?.jsonPrimitive?.contentOrNull ?: "un exploit"
            "Nouvel exploit débloqué : $name"
        }
        "GOAL_COMPLETED" -> {
            val days = obj["duration_days"]?.jsonPrimitive?.intOrNull
            if (days != null) "Objectif terminé — $days jours" else "Objectif terminé"
        }
        else -> "Nouvel événement"
    }
}
