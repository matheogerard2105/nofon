package com.nofon.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.network.AuthState
import com.nofon.feature.auth.AuthScreen
import com.nofon.feature.auth.AuthViewModel
import com.nofon.feature.auth.SettingsScreen
import com.nofon.feature.exploits.ExploitsScreen
import com.nofon.feature.group.GroupScreen
import com.nofon.feature.home.DayAppUsageScreen
import com.nofon.feature.home.GoalHistoryScreen
import com.nofon.feature.home.GoalsListScreen
import com.nofon.feature.home.HomeScreen
import com.nofon.feature.profile.FollowListKind
import com.nofon.feature.profile.FollowListScreen
import com.nofon.feature.profile.ProfileScreen
import com.nofon.feature.social.NotificationsScreen
import com.nofon.feature.social.SearchScreen
import com.nofon.feature.social.SocialScreen

private const val ROUTE_AUTH = "auth"
private const val ROUTE_MAIN = "main"

private const val ROUTE_SEARCH = "search"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val ROUTE_PROFILE_DETAIL = "profile/{userId}"
private const val ROUTE_FOLLOWERS = "followers/{userId}"
private const val ROUTE_FOLLOWING = "following/{userId}"
private const val ROUTE_GOAL_HISTORY = "history/{goalId}"
private const val ROUTE_DAY_APP_USAGE = "app-usage/{localDate}"
private const val ROUTE_GOALS_LIST = "goals"
private const val ROUTE_SETTINGS = "settings"
private const val ARG_USER_ID = "userId"
private const val ARG_GOAL_ID = "goalId"
private const val ARG_LOCAL_DATE = "localDate"

/**
 * NavHost racine : écran de connexion tant que l'utilisateur n'est pas authentifié,
 * shell à 5 onglets ensuite (PRODUCT_SPEC-2.md §8). `AuthScreen` observe lui-même
 * `AuthState` pour entrer (`onAuthenticated`) ; ce NavHost observe le même état,
 * exposé par le même repository singleton, pour ressortir vers l'écran de connexion
 * dès qu'une déconnexion survient (bouton temporaire dans le shell, en attendant le
 * vrai écran Profil).
 */
@Composable
fun NofonNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        val state = authState
        if (state is AuthState.SignedOut && navController.currentDestination?.route != ROUTE_AUTH) {
            navController.navigate(ROUTE_AUTH) {
                popUpTo(0)
            }
        } else if (state is AuthState.SignedIn) {
            // TODO-35 (PRODUCT_TODOS-2.md) : un compte supprimé (soft-delete, TODO-29)
            // reste connectable avec ses identifiants jusqu'à l'effacement dur différé
            // (30 jours) — bloqué ici, à chaque connexion fraîche ou session restaurée.
            authViewModel.verifyAccountStillActiveOrSignOut(state.userId)
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_AUTH) {
        composable(ROUTE_AUTH) {
            AuthScreen(
                onAuthenticated = {
                    navController.navigate(ROUTE_MAIN) {
                        popUpTo(ROUTE_AUTH) { inclusive = true }
                    }
                },
                viewModel = authViewModel
            )
        }
        composable(ROUTE_MAIN) {
            MainShell()
        }
    }
}

private enum class MainTab(val route: String, val label: String) {
    HOME("home", "Accueil"),
    SOCIAL("social", "Social"),
    GROUP("group", "Groupe"),
    EXPLOITS("exploits", "Exploits"),
    PROFILE("profile", "Profil")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell() {
    val tabController = rememberNavController()
    val backStackEntry: NavBackStackEntry? by tabController.currentBackStackEntryAsState()
    // Barre d'onglets masquée sur les écrans "détail" (profil d'un tiers, recherche,
    // notifications, listes d'abonnés) — premières destinations avec argument de ce
    // projet (voir ROUTE_PROFILE_DETAIL et suivantes), toutes poussées sur ce même
    // NavHost plutôt que sur le NavHost racine, pour rester dans une seule pile de retour.
    val showBottomBar = MainTab.entries.any { it.route == backStackEntry?.destination?.route }

    val colors = NofonTheme.colors

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("NOFON", style = NofonTextStyles.logo, color = colors.onSurface) },
                navigationIcon = {
                    // Visible uniquement sur les écrans "détail" (pas sur les 5 onglets
                    // racine, où il n'y a nulle part où revenir dans cette pile).
                    if (!showBottomBar) {
                        IconButton(onClick = { tabController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = colors.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    // Déconnexion/suppression de compte déplacées dans l'écran Paramètres
                    // (refonte du profil, 2026-09-04) — ce bouton n'ouvre plus que la
                    // navigation vers cet écran.
                    IconButton(onClick = { tabController.navigate(ROUTE_SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres", tint = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        },
        bottomBar = { if (showBottomBar) NofonBottomBar(tabController) }
    ) { padding ->
        NavHost(
            navController = tabController,
            startDestination = MainTab.HOME.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(MainTab.HOME.route) {
                HomeScreen(
                    onOpenHistory = { goalId -> tabController.navigate("history/$goalId") },
                    onOpenAppUsage = { localDate -> tabController.navigate("app-usage/$localDate") }
                )
            }
            composable(MainTab.SOCIAL.route) {
                SocialScreen(
                    onOpenProfile = { userId -> tabController.navigate("profile/$userId") },
                    onOpenSearch = { tabController.navigate(ROUTE_SEARCH) },
                    onOpenNotifications = { tabController.navigate(ROUTE_NOTIFICATIONS) }
                )
            }
            composable(MainTab.GROUP.route) {
                GroupScreen(onOpenProfile = { userId -> tabController.navigate("profile/$userId") })
            }
            composable(MainTab.EXPLOITS.route) { ExploitsScreen() }
            composable(MainTab.PROFILE.route) {
                ProfileScreen(
                    userId = null,
                    onOpenFollowers = { userId -> tabController.navigate("followers/$userId") },
                    onOpenFollowing = { userId -> tabController.navigate("following/$userId") },
                    onOpenGoalsList = { tabController.navigate(ROUTE_GOALS_LIST) },
                    onOpenExploits = { tabController.switchTab(MainTab.EXPLOITS) }
                )
            }

            composable(ROUTE_SEARCH) {
                SearchScreen(onOpenProfile = { userId -> tabController.navigate("profile/$userId") })
            }
            composable(ROUTE_NOTIFICATIONS) {
                NotificationsScreen(onOpenProfile = { userId -> tabController.navigate("profile/$userId") })
            }
            composable(
                ROUTE_PROFILE_DETAIL,
                arguments = listOf(navArgument(ARG_USER_ID) { type = NavType.StringType })
            ) { entry ->
                val userId = entry.arguments?.getString(ARG_USER_ID)
                ProfileScreen(
                    userId = userId,
                    onOpenFollowers = { id -> tabController.navigate("followers/$id") },
                    onOpenFollowing = { id -> tabController.navigate("following/$id") },
                    onOpenGoalsList = { tabController.navigate(ROUTE_GOALS_LIST) }
                )
            }
            composable(
                ROUTE_FOLLOWERS,
                arguments = listOf(navArgument(ARG_USER_ID) { type = NavType.StringType })
            ) { entry ->
                val userId = entry.arguments?.getString(ARG_USER_ID).orEmpty()
                FollowListScreen(
                    userId = userId,
                    kind = FollowListKind.FOLLOWERS,
                    onOpenProfile = { id -> tabController.navigate("profile/$id") }
                )
            }
            composable(
                ROUTE_FOLLOWING,
                arguments = listOf(navArgument(ARG_USER_ID) { type = NavType.StringType })
            ) { entry ->
                val userId = entry.arguments?.getString(ARG_USER_ID).orEmpty()
                FollowListScreen(
                    userId = userId,
                    kind = FollowListKind.FOLLOWING,
                    onOpenProfile = { id -> tabController.navigate("profile/$id") }
                )
            }
            composable(
                ROUTE_GOAL_HISTORY,
                arguments = listOf(navArgument(ARG_GOAL_ID) { type = NavType.StringType })
            ) { entry ->
                val goalId = entry.arguments?.getString(ARG_GOAL_ID).orEmpty()
                GoalHistoryScreen(
                    goalId = goalId,
                    onOpenAppUsage = { localDate -> tabController.navigate("app-usage/$localDate") }
                )
            }
            composable(
                ROUTE_DAY_APP_USAGE,
                arguments = listOf(navArgument(ARG_LOCAL_DATE) { type = NavType.StringType })
            ) { entry ->
                val localDate = entry.arguments?.getString(ARG_LOCAL_DATE).orEmpty()
                DayAppUsageScreen(localDate = localDate)
            }
            composable(ROUTE_GOALS_LIST) {
                GoalsListScreen(onOpenHistory = { goalId -> tabController.navigate("history/$goalId") })
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen()
            }
        }
    }
}

/** Bascule d'onglet racine — même comportement que la barre de navigation du bas, réutilisé pour "exploits par rareté -> onglet Exploits" (ProfileScreen). */
private fun NavHostController.switchTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun NofonBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val colors = NofonTheme.colors

    NavigationBar(containerColor = colors.navBarBackground) {
        MainTab.entries.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { navController.switchTab(tab) },
                icon = { Icon(tab.icon(), contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.accent,
                    selectedTextColor = colors.accent,
                    unselectedIconColor = colors.navInactive,
                    unselectedTextColor = colors.navInactive,
                    indicatorColor = colors.background
                )
            )
        }
    }
}

private fun MainTab.icon() = when (this) {
    MainTab.HOME -> Icons.Default.Home
    MainTab.SOCIAL -> Icons.Default.People
    MainTab.GROUP -> Icons.Default.Groups
    MainTab.EXPLOITS -> Icons.Default.EmojiEvents
    MainTab.PROFILE -> Icons.Default.Person
}
