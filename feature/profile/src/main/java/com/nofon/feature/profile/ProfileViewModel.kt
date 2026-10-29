package com.nofon.feature.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.designsystem.exploitRarityTierFor
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.DailyMeasurementPoint
import com.nofon.core.network.ExploitRepository
import com.nofon.core.network.PublicProfile
import com.nofon.core.network.SocialGraphRepository
import com.nofon.core.network.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: PublicProfile,
    val isSelf: Boolean,
    val isFollowing: Boolean
)

/**
 * TODO-18 amendé (PRODUCT_TODOS-2.md, 2026-09-03/04) : temps d'écran et déverrouillages
 * quotidiens exacts, publics sur le profil de n'importe qui — pas réservé à `isSelf`.
 */
enum class ScreenTimeRange(val label: String) {
    WEEK("7 j"),
    MONTH("30 j"),
    YEAR("1 an"),
    ALL_TIME("Depuis le début")
}

/**
 * `userId = null` (chargé via `load`) affiche le profil de l'utilisateur courant.
 * `comparison*` : sur le profil d'un tiers, nos propres données sont chargées en plus
 * pour la superposition demandée ("comparable au notre") — `null` sur son propre profil.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val socialGraphRepository: SocialGraphRepository,
    private val exploitRepository: ExploitRepository
) : ViewModel() {

    private val _state = MutableStateFlow<ProfileUiState?>(null)
    val state: StateFlow<ProfileUiState?> = _state.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private var targetUserId: String? = null

    private val _screenTimeRange = MutableStateFlow(ScreenTimeRange.WEEK)
    val screenTimeRange: StateFlow<ScreenTimeRange> = _screenTimeRange.asStateFlow()

    private val _measurements = MutableStateFlow<List<DailyMeasurementPoint>>(emptyList())
    val measurements: StateFlow<List<DailyMeasurementPoint>> = _measurements.asStateFlow()

    private val _comparisonMeasurements = MutableStateFlow<List<DailyMeasurementPoint>?>(null)
    val comparisonMeasurements: StateFlow<List<DailyMeasurementPoint>?> = _comparisonMeasurements.asStateFlow()

    private val _exploitCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val exploitCounts: StateFlow<Map<String, Int>> = _exploitCounts.asStateFlow()

    private val _comparisonExploitCounts = MutableStateFlow<Map<String, Int>?>(null)
    val comparisonExploitCounts: StateFlow<Map<String, Int>?> = _comparisonExploitCounts.asStateFlow()

    /** "Toi" quand une comparaison est active, sinon `null` — jamais le handle du viewer, inutile ici. */
    private val _comparisonLabel = MutableStateFlow<String?>(null)
    val comparisonLabel: StateFlow<String?> = _comparisonLabel.asStateFlow()

    fun load(userId: String?) {
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val resolvedId = userId ?: selfId
        targetUserId = resolvedId
        val isSelf = resolvedId == selfId
        _comparisonLabel.value = if (isSelf) null else "Toi"

        viewModelScope.launch {
            val profile = profileRepository.publicProfile(resolvedId).getOrNull() ?: return@launch
            val following = if (isSelf) {
                false
            } else {
                socialGraphRepository.isFollowing(selfId, resolvedId).getOrDefault(false)
            }
            _state.value = ProfileUiState(profile = profile, isSelf = isSelf, isFollowing = following)
        }
        loadMeasurements(resolvedId, selfId, isSelf)
        loadExploitCounts(resolvedId, selfId, isSelf)
    }

    fun selectScreenTimeRange(range: ScreenTimeRange) {
        _screenTimeRange.value = range
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val target = targetUserId ?: return
        loadMeasurements(target, selfId, target == selfId)
    }

    private fun loadMeasurements(targetId: String, selfId: String, isSelf: Boolean) {
        val since = when (_screenTimeRange.value) {
            ScreenTimeRange.WEEK -> LocalDate.now().minusDays(6)
            ScreenTimeRange.MONTH -> LocalDate.now().minusDays(29)
            ScreenTimeRange.YEAR -> LocalDate.now().minusDays(364)
            ScreenTimeRange.ALL_TIME -> null
        }?.toString()
        viewModelScope.launch {
            _measurements.value = profileRepository.dailyMeasurements(targetId, since).getOrDefault(emptyList())
        }
        if (isSelf) {
            _comparisonMeasurements.value = null
        } else {
            viewModelScope.launch {
                _comparisonMeasurements.value = profileRepository.dailyMeasurements(selfId, since).getOrDefault(emptyList())
            }
        }
    }

    private fun loadExploitCounts(targetId: String, selfId: String, isSelf: Boolean) {
        viewModelScope.launch {
            _exploitCounts.value = exploitTierCounts(targetId)
        }
        if (isSelf) {
            _comparisonExploitCounts.value = null
        } else {
            viewModelScope.launch {
                _comparisonExploitCounts.value = exploitTierCounts(selfId)
            }
        }
    }

    /**
     * TODO-33 (PRODUCT_TODOS-2.md) : rareté recalculée en direct depuis le pourcentage
     * réel (`exploit_rarity_stats`), pas depuis `rarity_at_unlock` (figé, désormais
     * ignoré pour l'affichage — même changement que l'écran Exploits). Un exploit dont
     * le pourcentage n'est pas encore fiable (`exploitRarityTierFor` renvoie `null`,
     * "Nouveau") ne compte dans aucun des quatre paliers.
     */
    private suspend fun exploitTierCounts(userId: String): Map<String, Int> {
        val unlocked = exploitRepository.unlockedFor(userId).getOrDefault(emptyList())
        val percentageByCode = exploitRepository.rarityStats().getOrDefault(emptyList())
            .associate { it.exploitCode to it.percentage }
        return unlocked
            .mapNotNull { exploitRarityTierFor(percentageByCode[it.exploitCode]) }
            .groupingBy { it.name }
            .eachCount()
    }

    fun toggleFollow() {
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val target = targetUserId ?: return
        val current = _state.value ?: return
        viewModelScope.launch {
            if (current.isFollowing) {
                socialGraphRepository.unfollow(selfId, target)
            } else {
                socialGraphRepository.follow(selfId, target)
            }
            load(target)
        }
    }

    fun block() {
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val target = targetUserId ?: return
        viewModelScope.launch { socialGraphRepository.block(selfId, target) }
    }

    fun report(reason: String) {
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val target = targetUserId ?: return
        viewModelScope.launch { socialGraphRepository.report(selfId, "USER", target, reason) }
    }

    fun updateHandle(newHandle: String) {
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        if (newHandle.isBlank()) return
        viewModelScope.launch {
            profileRepository.updateHandle(selfId, newHandle.trim())
                .onSuccess { load(null) }
                .onFailure { _actionError.value = it.message ?: "Ce pseudo est peut-être déjà pris." }
        }
    }

    fun updateAvatar(context: Context, imageUri: Uri) {
        val selfId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        viewModelScope.launch {
            profileRepository.uploadAvatar(selfId, context, imageUri)
                .onSuccess { load(null) }
                .onFailure { _actionError.value = it.message ?: "Impossible de mettre à jour la photo." }
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }
}
