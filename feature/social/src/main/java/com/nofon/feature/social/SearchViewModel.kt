package com.nofon.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.SocialGraphRepository
import com.nofon.core.network.UserSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val socialGraphRepository: SocialGraphRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val results: StateFlow<List<UserSearchResult>> = _results.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        if (value.isBlank()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _results.value = socialGraphRepository.searchUsers(value, excludingBlockedBy = userId)
                .getOrDefault(emptyList())
        }
    }
}
