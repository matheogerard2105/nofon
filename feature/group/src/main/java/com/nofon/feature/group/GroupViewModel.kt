package com.nofon.feature.group

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.designsystem.groupMemberColor
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.GoalRepository
import com.nofon.core.network.Group
import com.nofon.core.network.GroupMember
import com.nofon.core.network.GroupMemberDailyProgress
import com.nofon.core.network.GroupRepository
import com.nofon.core.network.SocialGraphRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MemberUiState(
    val userId: String,
    val handle: String,
    val avatarUrl: String?,
    val status: String,
    val leftEffectiveAt: String?
)

data class MemberCardUiState(
    val userId: String,
    val handle: String,
    val avatarUrl: String?,
    val status: String,
    val color: Color,
    /** `null` tant que le résumé d'objectif n'est pas encore chargé/disponible pour ce membre. */
    val constraintType: String?,
    val timeLimitSeconds: Int?,
    val opensLimit: Int?
)

/** Une courbe du graphique d'évolution — un point par jour où une mesure existe, `null` = trou visuel. */
data class MemberProgressSeries(
    val userId: String,
    val color: Color,
    /** % de la limite avant dépassement (ex. 30 min / 60 = 50.0), peut dépasser 100 en cas d'échec. */
    val points: List<Pair<LocalDate, Float?>>
)

sealed interface GroupUiState {
    data object Loading : GroupUiState

    /** Aucun objectif payant actif — fonctionnalité inaccessible (demande explicite). */
    data object Locked : GroupUiState

    data object NoMembership : GroupUiState
    data class PendingApproval(val group: Group) : GroupUiState
    data class Dashboard(
        val group: Group,
        val isCreator: Boolean,
        val myMembership: MemberUiState,
        val members: List<MemberCardUiState>,
        val pendingRequests: List<MemberUiState>,
        val progressSeries: List<MemberProgressSeries>,
        /** % du jour même, recalculé à chaque rafraîchissement de l'écran (demande explicite). */
        val todayPercentByUserId: Map<String, Float>
    ) : GroupUiState
}

/**
 * `group_success` est entièrement calculé côté serveur (`0006_groups.sql`) — ce
 * ViewModel ne fait jamais ce calcul, seulement lire l'état déjà résolu et déclencher
 * les actions (créer/rejoindre/accepter/quitter/exclure/renommer/dissoudre).
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val groupRepository: GroupRepository,
    private val goalRepository: GoalRepository,
    private val socialGraphRepository: SocialGraphRepository
) : ViewModel() {

    private val _state = MutableStateFlow<GroupUiState>(GroupUiState.Loading)
    val state: StateFlow<GroupUiState> = _state.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init {
        refresh()
    }

    private fun currentUserId(): String? = (authRepository.authState.value as? AuthState.SignedIn)?.userId

    fun refresh() {
        val userId = currentUserId() ?: return
        viewModelScope.launch {
            val membership = groupRepository.myMembership(userId).getOrNull()
            if (membership == null) {
                val hasGoal = goalRepository.activeStakedGoal(userId).getOrNull() != null
                _state.value = if (hasGoal) GroupUiState.NoMembership else GroupUiState.Locked
                return@launch
            }

            val group = groupRepository.groupById(membership.groupId).getOrNull() ?: run {
                _state.value = GroupUiState.NoMembership
                return@launch
            }

            if (membership.status == "PENDING") {
                _state.value = GroupUiState.PendingApproval(group)
                return@launch
            }

            val allMembers = groupRepository.members(membership.groupId).getOrDefault(emptyList())
            val handles = socialGraphRepository.usersByIds(allMembers.map { it.userId })
                .getOrDefault(emptyList())
                .associateBy { it.id }

            fun toUiState(m: GroupMember) = MemberUiState(
                userId = m.userId,
                handle = handles[m.userId]?.handle ?: m.userId,
                avatarUrl = handles[m.userId]?.avatarUrl,
                status = m.status,
                leftEffectiveAt = m.leftEffectiveAt
            )

            val dailyResults = groupRepository.dailyResults(membership.groupId).getOrDefault(emptyList())

            val activeMembers = allMembers.filter { it.status in setOf("ACTIVE", "LEAVING") }
                .sortedBy { it.createdAt }
            val goalSummaries = groupRepository.memberGoalSummaries(membership.groupId)
                .getOrDefault(emptyList())
                .associateBy { it.userId }
            val memberCards = activeMembers.mapIndexed { index, m ->
                val summary = goalSummaries[m.userId]
                MemberCardUiState(
                    userId = m.userId,
                    handle = handles[m.userId]?.handle ?: m.userId,
                    avatarUrl = handles[m.userId]?.avatarUrl,
                    status = m.status,
                    color = groupMemberColor(index),
                    constraintType = summary?.constraintType,
                    timeLimitSeconds = summary?.timeLimitSeconds,
                    opensLimit = summary?.opensLimit
                )
            }
            val colorByUserId = memberCards.associate { it.userId to it.color }

            val progress = groupRepository.memberDailyProgress(membership.groupId).getOrDefault(emptyList())
            val progressSeries = buildProgressSeries(progress, colorByUserId, group.createdAt)
            val todayPercent = progress
                .filter { it.localDate == LocalDate.now().toString() }
                .mapNotNull { p -> percentFor(p)?.let { p.userId to it } }
                .toMap()

            _state.value = GroupUiState.Dashboard(
                group = group,
                isCreator = group.creatorId == userId,
                myMembership = toUiState(allMembers.first { it.userId == userId }),
                members = memberCards,
                pendingRequests = allMembers.filter { it.status == "PENDING" }.map(::toUiState),
                progressSeries = progressSeries,
                todayPercentByUserId = todayPercent
            )
        }
    }

    fun createGroup() {
        val userId = currentUserId() ?: return
        viewModelScope.launch {
            val goal = goalRepository.activeStakedGoal(userId).getOrNull()
            if (goal == null) {
                _actionError.value = "Un objectif payant actif est nécessaire pour créer un groupe."
                return@launch
            }
            groupRepository.createGroup(userId, goal.id)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun joinByCode(code: String) {
        val userId = currentUserId() ?: return
        viewModelScope.launch {
            val goal = goalRepository.activeStakedGoal(userId).getOrNull()
            if (goal == null) {
                _actionError.value = "Un objectif payant actif est nécessaire pour rejoindre un groupe."
                return@launch
            }
            val group = groupRepository.groupByCode(code.trim().uppercase()).getOrNull()
            if (group == null) {
                _actionError.value = "Code introuvable."
                return@launch
            }
            groupRepository.requestToJoin(group.id, userId, goal.id)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun acceptRequest(groupId: String, userId: String) {
        viewModelScope.launch {
            groupRepository.acceptRequest(groupId, userId)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun rejectRequest(groupId: String, userId: String) {
        viewModelScope.launch {
            groupRepository.rejectRequest(groupId, userId)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun leaveGroup() {
        val userId = currentUserId() ?: return
        val dashboard = _state.value as? GroupUiState.Dashboard ?: return
        viewModelScope.launch {
            groupRepository.leaveGroup(dashboard.group.id, userId)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun removeMember(userId: String) {
        val dashboard = _state.value as? GroupUiState.Dashboard ?: return
        viewModelScope.launch {
            groupRepository.removeMember(dashboard.group.id, userId)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun dissolveGroup() {
        val dashboard = _state.value as? GroupUiState.Dashboard ?: return
        viewModelScope.launch {
            groupRepository.dissolveGroup(dashboard.group.id)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun renameGroup(name: String) {
        val dashboard = _state.value as? GroupUiState.Dashboard ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            groupRepository.renameGroup(dashboard.group.id, name.trim())
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun changeGroupAvatar(context: Context, imageUri: Uri) {
        val dashboard = _state.value as? GroupUiState.Dashboard ?: return
        viewModelScope.launch {
            groupRepository.uploadGroupAvatar(dashboard.group.id, context, imageUri)
                .onFailure { _actionError.value = it.message }
            refresh()
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }
}

/** TIME -> ratio temps, OPENS -> ratio ouvertures, BOTH -> le pire des deux (le plus proche du dépassement). */
private fun percentFor(p: GroupMemberDailyProgress): Float? {
    val timeRatio = p.timeLimitSeconds?.takeIf { it > 0 }
        ?.let { limit -> p.measuredTimeSeconds?.let { it.toFloat() / limit } }
    val opensRatio = p.opensLimit?.takeIf { it > 0 }
        ?.let { limit -> p.measuredOpens?.let { it.toFloat() / limit } }
    val ratio = when (p.constraintType) {
        "TIME" -> timeRatio
        "OPENS" -> opensRatio
        "BOTH" -> listOfNotNull(timeRatio, opensRatio).maxOrNull()
        else -> null
    }
    return ratio?.times(100f)
}

private fun buildProgressSeries(
    progress: List<GroupMemberDailyProgress>,
    colorByUserId: Map<String, Color>,
    groupCreatedAt: String
): List<MemberProgressSeries> {
    val since = runCatching { LocalDate.parse(groupCreatedAt.substring(0, 10)) }.getOrElse { LocalDate.now() }
    return progress.groupBy { it.userId }
        .mapNotNull { (userId, entries) ->
            val color = colorByUserId[userId] ?: return@mapNotNull null
            val points = entries
                .filter { it.localDate != null && it.status != null && it.status != "PENDING" }
                .filter { LocalDate.parse(it.localDate) >= since }
                .sortedBy { it.localDate }
                .map { LocalDate.parse(it.localDate) to (if (it.status == "NO_DATA") null else percentFor(it)) }
            MemberProgressSeries(userId, color, points)
        }
}
