package com.nofon.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.network.AuthState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Écran unique connexion/inscription. `onAuthenticated` est appelé dès que
 * `AuthState` passe à `SignedIn` — c'est le NavHost de `:app` qui décide alors
 * d'afficher le shell à 5 onglets, pas cet écran.
 */
@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = NofonTheme.colors

    LaunchedEffect(authState) {
        if (authState is AuthState.SignedIn) onAuthenticated()
    }

    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var birthDateText by remember { mutableStateOf("") }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.divider,
        cursorColor = colors.accent,
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.onSurfaceVariant
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "NOFON",
            style = NofonTextStyles.logo,
            color = colors.onSurface
        )
        Text(
            if (isSignUp) "Créer un compte" else "Se connecter",
            style = NofonTextStyles.heading,
            color = colors.onSurface
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            visualTransformation = PasswordVisualTransformation(),
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        if (isSignUp) {
            OutlinedTextField(
                value = birthDateText,
                onValueChange = { birthDateText = it },
                label = { Text("Date de naissance (JJ/MM/AAAA)") },
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Nofon est réservé aux 18 ans et plus (intégralité du produit, comptes gratuits inclus).",
                style = NofonTextStyles.small,
                color = colors.onSurfaceVariant
            )
        }

        uiState.error?.let {
            Text(it, color = colors.error, style = NofonTextStyles.body)
        }

        if (uiState.loading) {
            CircularProgressIndicator(
                color = colors.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else {
            val parsedBirthDate = parseBirthDate(birthDateText)
            Button(
                onClick = {
                    if (isSignUp) {
                        parsedBirthDate?.let { viewModel.signUp(email, password, it) }
                    } else {
                        viewModel.signIn(email, password)
                    }
                },
                enabled = email.isNotBlank() && password.isNotBlank() && (!isSignUp || parsedBirthDate != null),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.surfaceVariant,
                    disabledContentColor = colors.onSurfaceTertiary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSignUp) "S'inscrire" else "Se connecter", style = NofonTextStyles.bodyEmphasis)
            }

            OutlinedButton(
                onClick = { viewModel.signInWithGoogle(context) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continuer avec Google", style = NofonTextStyles.body)
            }
        }

        TextButton(onClick = { isSignUp = !isSignUp }) {
            Text(
                if (isSignUp) "J'ai déjà un compte" else "Créer un compte",
                color = colors.accent,
                style = NofonTextStyles.body
            )
        }
    }
}

private val birthDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun parseBirthDate(text: String): LocalDate? = try {
    LocalDate.parse(text, birthDateFormatter)
} catch (e: DateTimeParseException) {
    null
}
