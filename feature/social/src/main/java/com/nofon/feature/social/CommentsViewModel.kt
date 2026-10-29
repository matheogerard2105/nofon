package com.nofon.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.FeedRepository
import com.nofon.core.network.SocialGraphRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommentUiState(
    val id: String,
    val authorId: String,
    val authorHandle: String,
    val authorAvatarUrl: String?,
    val body: String
)

@HiltViewModel
class CommentsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val feedRepository: FeedRepository,
    private val socialGraphRepository: SocialGraphRepository
) : ViewModel() {

    private val _comments = MutableStateFlow<List<CommentUiState>>(emptyList())
    val comments: StateFlow<List<CommentUiState>> = _comments.asStateFlow()

    private var openFeedEventId: String? = null

    val currentUserId: String?
        get() = (authRepository.authState.value as? AuthState.SignedIn)?.userId

    fun open(feedEventId: String) {
        if (openFeedEventId == feedEventId) return
        openFeedEventId = feedEventId
        reload()
    }

    fun post(body: String) {
        val feedEventId = openFeedEventId ?: return
        val userId = currentUserId ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            feedRepository.postComment(feedEventId, userId, body)
            reload()
        }
    }

    fun delete(commentId: String) {
        viewModelScope.launch {
            feedRepository.deleteComment(commentId)
            reload()
        }
    }

    private fun reload() {
        val feedEventId = openFeedEventId ?: return
        viewModelScope.launch {
            val rawComments = feedRepository.commentsFor(feedEventId).getOrDefault(emptyList())
            val handles = socialGraphRepository.usersByIds(rawComments.map { it.authorId }.distinct())
                .getOrDefault(emptyList())
                .associateBy { it.id }
            _comments.value = rawComments.map { comment ->
                CommentUiState(
                    id = comment.id,
                    authorId = comment.authorId,
                    authorHandle = handles[comment.authorId]?.handle ?: comment.authorId,
                    authorAvatarUrl = handles[comment.authorId]?.avatarUrl,
                    body = comment.body
                )
            }
        }
    }
}
