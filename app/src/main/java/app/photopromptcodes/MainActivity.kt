package app.photopromptcodes

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import app.photopromptcodes.ui.AboutScreen
import app.photopromptcodes.ui.CodeDetailScreen
import app.photopromptcodes.ui.FavoritesScreen
import app.photopromptcodes.ui.GalleryScreen
import app.photopromptcodes.ui.HistoryScreen
import app.photopromptcodes.ui.LoginScreen
import app.photopromptcodes.ui.OnboardingScreen
import app.photopromptcodes.ui.PrivacyPolicyScreen
import app.photopromptcodes.ui.ResultDetailScreen
import app.photopromptcodes.ui.RunScreen
import app.photopromptcodes.ui.SettingsScreen
import app.photopromptcodes.ui.isLoggedInFlag
import app.photopromptcodes.ui.isOnboarded
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.PhotoPromptCodesTheme
import app.photopromptcodes.ui.theme.SurfaceDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoPromptCodesTheme {
                AppNav()
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("gallery", "Prompts", Icons.Filled.Home),
    Tab("favorites", "Favorites", Icons.Filled.Star),
    Tab("history", "History", Icons.Filled.Refresh),
    Tab("settings", "Settings", Icons.Filled.Settings)
)

private val TAB_ROUTES = TABS.map { it.route }.toSet()

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingUpdate by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<app.photopromptcodes.update.UpdateChecker.UpdateInfo?>(null)
    }
    // Silent update check on launch
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Only auto-check once per day
        val prefs = ctx.getSharedPreferences("ppc_prefs", android.content.Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_update_check", 0)
        if (System.currentTimeMillis() - lastCheck > 24 * 60 * 60 * 1000) {
            val info = app.photopromptcodes.update.UpdateChecker.checkForUpdate(ctx)
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            if (info != null) pendingUpdate = info
        }
    }
    val start = remember {
        when {
            !isOnboarded(ctx) -> "onboarding"
            !isLoggedInFlag(ctx) -> "login"
            else -> "gallery"
        }
    }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = SurfaceDark) {
                    TABS.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    nav.navigate(tab.route) {
                                        popUpTo("gallery") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (selected) Amber else PaperDim
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    color = if (selected) Amber else PaperDim
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = SurfaceDark
                            )
                        )
                    }
                }
            }
        },
        containerColor = NearBlack
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(innerPadding)
        ) {
        composable("onboarding") {
            OnboardingScreen(
                onDone = {
                    nav.navigate(if (isLoggedInFlag(ctx)) "gallery" else "login") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }
        composable("login") {
            LoginScreen(
                onDone = {
                    nav.navigate("gallery") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }
        composable("gallery") {
            GalleryScreen(
                onOpenCode = { code -> nav.navigate("detail/${Uri.encode(code)}") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onSignIn = { nav.navigate("signin") },
                onPrivacy = { nav.navigate("privacy") },
                onAbout = { nav.navigate("about") }
            )
        }
        composable("privacy") {
            PrivacyPolicyScreen(onBack = { nav.popBackStack() })
        }
        composable("signin") {
            LoginScreen(
                onDone = { nav.popBackStack() }
            )
        }
        composable(
            "detail/{code}",
            arguments = listOf(navArgument("code") { type = NavType.StringType })
        ) { entry ->
            val code = Uri.decode(entry.arguments?.getString("code").orEmpty())
            CodeDetailScreen(
                code = code,
                onBack = { nav.popBackStack() },
                onEdit = { photoUri ->
                    nav.navigate("run/${Uri.encode(code)}?photo=${Uri.encode(photoUri.toString())}")
                }
            )
        }
        composable(
            "run/{code}?photo={photo}",
            arguments = listOf(
                navArgument("code") { type = NavType.StringType },
                navArgument("photo") { type = NavType.StringType }
            )
        ) { entry ->
            val code = Uri.decode(entry.arguments?.getString("code").orEmpty())
            val photo = Uri.decode(entry.arguments?.getString("photo").orEmpty())
            RunScreen(
                code = code,
                photoUriString = photo,
                onBack = { nav.popBackStack() },
                onDone = { recordId ->
                    nav.navigate("result/$recordId") { popUpTo("gallery") }
                }
            )
        }
        composable("history") {
            HistoryScreen(
                onOpen = { id -> nav.navigate("result/$id") }
            )
        }
        composable("favorites") {
            FavoritesScreen(
                onOpen = { id -> nav.navigate("result/$id") }
            )
        }
        composable("about") {
            AboutScreen(
                onBack = { nav.popBackStack() },
                onPrivacy = { nav.navigate("privacy") }
            )
        }
        composable(
            "result/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ResultDetailScreen(
                recordId = id,
                onBack = { nav.popBackStack() }
            )
        }
        }
    }

    // Update-available dialog
    val update = pendingUpdate
    if (update != null) {
        var downloading by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingUpdate = null },
            title = { Text("Update available") },
            text = {
                Text(
                    "Version ${update.versionName} is ready." +
                        (if (update.notes.isNotBlank()) "\n\n${update.notes.take(300)}" else "")
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        downloading = true
                        scope.launch {
                            val ok = app.photopromptcodes.update.UpdateChecker
                                .downloadAndInstall(ctx, update)
                            if (ok) pendingUpdate = null
                            downloading = false
                        }
                    },
                    enabled = !downloading
                ) {
                    Text(if (downloading) "Downloading…" else "Update now")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingUpdate = null }) {
                    Text("Later")
                }
            }
        )
    }
}
