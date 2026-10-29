package com.nofon.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
fun SearchScreen(
    onOpenProfile: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    Column(Modifier.fillMaxSize().background(colors.background).padding(18.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Rechercher un pseudo", style = NofonTextStyles.body) },
            textStyle = NofonTextStyles.body,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.onSurfaceVariant) },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.divider,
                cursorColor = colors.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(Modifier.padding(top = 10.dp)) {
            items(results, key = { it.id }) { user ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProfile(user.id) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Avatar(url = user.avatarUrl, size = 40.dp)
                    Text(user.handle.capitalizedHandle(), style = NofonTextStyles.body, color = colors.onSurface)
                }
            }
            if (query.isNotBlank()) {
                item {
                    EndOfListNote(if (results.isEmpty()) "Aucun résultat." else "Fin des résultats.")
                }
            }
        }
    }
}
