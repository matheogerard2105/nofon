package com.nofon.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.SocialGraphRepository
import com.nofon.core.network.UserSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FollowListKind { FOLLOWERS, FOLLOWING }

@HiltViewModel
class FollowListViewModel @Inject constructor(
    private val socialGraphRepository: SocialGraphRepository
) : ViewModel() {

    private val _users = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val users: StateFlow<List<UserSearchResult>> = _users.asStateFlow()

    fun load(userId: String, kind: FollowListKind) {
        viewModelScope.launch {
            val result = when (kind) {
                FollowListKind.FOLLOWERS -> socialGraphRepository.followers(userId)
                FollowListKind.FOLLOWING -> socialGraphRepository.following(userId)
            }
            _users.value = result.getOrDefault(emptyList())
        }
    }
}
