package com.nofon.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.Avatar
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.capitalizedHandle

@Composable
fun FollowListScreen(
    userId: String,
    kind: FollowListKind,
    onOpenProfile: (String) -> Unit,
    viewModel: FollowListViewModel = hiltViewModel()
) {
    LaunchedEffect(userId, kind) { viewModel.load(userId, kind) }
    val users by viewModel.users.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    LazyColumn(Modifier.fillMaxSize().background(colors.background)) {
        items(users, key = { it.id }) { user ->
            ListItem(
                leadingContent = { Avatar(url = user.avatarUrl, size = 40.dp) },
                headlineContent = { Text(user.handle.capitalizedHandle(), style = NofonTextStyles.body, color = colors.onSurface) },
                colors = ListItemDefaults.colors(containerColor = colors.background),
                modifier = Modifier.fillMaxWidth().clickable { onOpenProfile(user.id) }
            )
        }
        item { EndOfListNote(if (users.isEmpty()) "Personne pour l'instant." else "Fin de la liste.") }
    }
}
