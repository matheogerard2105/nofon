package com.nofon.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.designsystem.ExploitRarityTier
import com.nofon.core.designsystem.exploitRarityTierFor
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.ExploitRepository
import com.nofon.core.network.FeedEvent
import com.nofon.core.network.FeedRepository
import com.nofon.core.network.NotificationRepository
import com.nofon.core.network.SocialGraphRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FeedItemUiState(
    val event: FeedEvent,
    val authorHandle: String,
    val authorAvatarUrl: String?,
    val reactionCount: Long,
    val commentCount: Long,
    val didIReact: Boolean,
    /** Non nul uniquement pour `EXPLOIT_UNLOCKED` — rareté recalculée en direct (TODO-33), jamais figée. */
    val exploitDetail: ExploitFeedDetail? = null
)

/**
 * Contexte enrichi d'un exploit affiché dans le fil. `viewerUnlocked` est calculé par
 * rapport à CELUI QUI REGARDE le post, pas l'auteur — deux personnes voient le même post
 * avec un trophée ou un cadenas selon leur propre progression (demande explicite).
 */
data class ExploitFeedDetail(
    val name: String,
    val description: String,
    val tier: ExploitRarityTier?,
    val percentage: Double?,
    val viewerUnlocked: Boolean
)

/**
 * Fil chronologique (PRODUCT_SPEC-2.md §10 : uniquement les utilisateurs suivis, aucune
 * recommandation algorithmique). Une seule page à la fois (pas de scroll infini pour
 * cette itération — `FeedRepository.feed` expose déjà la pagination keyset côté repo,
 * le scroll infini reste à brancher si le besoin se confirme).
 */
@HiltViewModel
class SocialViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val feedRepository: FeedRepository,
    private val notificationRepository: NotificationRepository,
    private val socialGraphRepository: SocialGraphRepository,
    private val exploitRepository: ExploitRepository
) : ViewModel() {

    private val _items = MutableStateFlow<List<FeedItemUiState>>(emptyList())
    val items: StateFlow<List<FeedItemUiState>> = _items.asStateFlow()

    private val _unreadNotifications = MutableStateFlow(0L)
    val unreadNotifications: StateFlow<Long> = _unreadNotifications.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        viewModelScope.launch {
            _loading.value = true
            val events = feedRepository.feed().getOrDefault(emptyList())
            val engagement = feedRepository.engagementFor(events.map { it.id })
                .getOrDefault(emptyList())
                .associateBy { it.feedEventId }
            val handles = socialGraphRepository.usersByIds(events.map { it.userId }.distinct())
                .getOrDefault(emptyList())
                .associateBy { it.id }
            val exploitDetails = exploitDetailsFor(events, userId)

            _items.value = events.map { event ->
                val stats = engagement[event.id]
                FeedItemUiState(
                    event = event,
                    authorHandle = handles[event.userId]?.handle ?: event.userId,
                    authorAvatarUrl = handles[event.userId]?.avatarUrl,
                    reactionCount = stats?.reactionCount ?: 0,
                    commentCount = stats?.commentCount ?: 0,
                    didIReact = stats?.didIReact ?: false,
                    exploitDetail = exploitCodeOf(event)?.let { exploitDetails[it] }
                )
            }
            _unreadNotifications.value = notificationRepository.unreadCount(userId).getOrDefault(0)
            _loading.value = false
        }
    }

    fun toggleReaction(feedEventId: String) {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val current = _items.value.find { it.event.id == feedEventId } ?: return
        viewModelScope.launch {
            if (current.didIReact) {
                feedRepository.unreact(feedEventId, userId)
            } else {
                feedRepository.react(feedEventId, userId)
            }
            refresh()
        }
    }

    private fun exploitCodeOf(event: FeedEvent): String? =
        if (event.eventType == "EXPLOIT_UNLOCKED") {
            event.content.jsonObject["exploit_code"]?.jsonPrimitive?.contentOrNull
        } else {
            null
        }

    /**
     * Charge le contexte des exploits présents dans cette page de fil, en une seule
     * passe. `viewerUserId` est CELUI QUI CONSULTE le fil — le trophée/cadenas affiché
     * dépend de sa propre progression, jamais de celle de l'auteur du post.
     */
    private suspend fun exploitDetailsFor(events: List<FeedEvent>, viewerUserId: String): Map<String, ExploitFeedDetail> {
        val codes = events.mapNotNull(::exploitCodeOf).toSet()
        if (codes.isEmpty()) return emptyMap()

        val definitions = exploitRepository.definitions().getOrDefault(emptyList()).associateBy { it.code }
        val stats = exploitRepository.rarityStats().getOrDefault(emptyList()).associateBy { it.exploitCode }
        val viewerUnlocked = exploitRepository.unlockedFor(viewerUserId).getOrDefault(emptyList())
            .map { it.exploitCode }.toSet()

        return codes.associateWith { code ->
            val def = definitions[code]
            val stat = stats[code]
            ExploitFeedDetail(
                name = def?.name ?: code,
                description = def?.description.orEmpty(),
                tier = exploitRarityTierFor(stat?.percentage),
                percentage = stat?.percentage,
                viewerUnlocked = viewerUnlocked.contains(code)
            )
        }
    }
}
