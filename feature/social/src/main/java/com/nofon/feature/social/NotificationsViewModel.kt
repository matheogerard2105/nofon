package com.nofon.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.NotificationRepository
import com.nofon.core.network.SocialGraphRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotificationUiState(
    val id: String,
    val type: String,
    val actorId: String?,
    val actorHandle: String?,
    val actorAvatarUrl: String?
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val notificationRepository: NotificationRepository,
    private val socialGraphRepository: SocialGraphRepository
) : ViewModel() {

    private val _notifications = MutableStateFlow<List<NotificationUiState>>(emptyList())
    val notifications: StateFlow<List<NotificationUiState>> = _notifications.asStateFlow()

    init {
        refresh()
        markAllRead()
    }

    fun refresh() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        viewModelScope.launch {
            val rawNotifications = notificationRepository.list(userId).getOrDefault(emptyList())
            val actorIds = rawNotifications.mapNotNull { it.actorId }.distinct()
            val handles = socialGraphRepository.usersByIds(actorIds).getOrDefault(emptyList()).associateBy { it.id }
            // `usersByIds` s'appuie sur la RLS de `users` (deleted_at is null, 0001_init.sql)
            // : un acteur absent de `handles` a supprimé son compte — la notification n'a
            // plus de sens à afficher (demande explicite), pas de repli sur un UUID brut.
            _notifications.value = rawNotifications
                .filter { it.actorId == null || handles.containsKey(it.actorId) }
                .map { notification ->
                    NotificationUiState(
                        id = notification.id,
                        type = notification.type,
                        actorId = notification.actorId,
                        actorHandle = notification.actorId?.let { handles[it]?.handle ?: it },
                        actorAvatarUrl = notification.actorId?.let { handles[it]?.avatarUrl }
                    )
                }
        }
    }

    private fun markAllRead() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        viewModelScope.launch { notificationRepository.markAllRead(userId) }
    }
}
