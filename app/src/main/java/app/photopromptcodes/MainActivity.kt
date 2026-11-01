package app.photopromptcodes

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.photopromptcodes.ui.AboutScreen
import app.photopromptcodes.ui.BatchRunScreen
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
    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoPromptCodesTheme {
                AppNav(resumeTick = resumeTick.value)
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
fun AppNav(resumeTick: Int) {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pendingUpdate by remember {
        mutableStateOf<app.photopromptcodes.update.UpdateChecker.UpdateInfo?>(null)
    }
    // Forced-update check: on every launch and every return from the
    // background. At most once per 15 minutes (GitHub's unauthenticated
    // API allows 60 calls/hour), and never while the gate is already up.
    // A failed check (no network, GitHub down) returns null, so the app
    // opens normally instead of locking the user out.
    androidx.compose.runtime.LaunchedEffect(resumeTick) {
        if (pendingUpdate != null) return@LaunchedEffect
        val prefs = ctx.getSharedPreferences("ppc_prefs", android.content.Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_update_check", 0)
        if (System.currentTimeMillis() - lastCheck < 15 * 60 * 1000L) return@LaunchedEffect
        val info = app.photopromptcodes.update.UpdateChecker.checkForUpdate(ctx)
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        if (info != null) pendingUpdate = info
    }
    // The gate replaces the whole app: no gallery, no runs, no back
    // door until the update is installed.
    val update = pendingUpdate
    if (update != null) {
        app.photopromptcodes.ui.ForceUpdateGate(update = update)
        return
    }
    val start = remember {
        when {
            !isOnboarded(ctx) -> "onboarding"
            else -> "gallery"
        }
    }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // No fixed height: NavigationBar sizes itself around the
                // system navigation-bar inset. A fixed height (v1.8.5's
                // 60dp cap) squeezed that inset padding and crushed the
                // bar underneath the phone's own buttons.
                NavigationBar(
                    containerColor = SurfaceDark
                ) {
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
                                    tint = if (selected) Amber else PaperDim,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    color = if (selected) Amber else PaperDim,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent
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
                    nav.navigate("gallery") {
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
                onOpenCode = { code -> nav.navigate("detail/${Uri.encode(code)}") },
                onCustomPrompt = { prompt, photoUri ->
                    nav.navigate("runcustom?photo=${Uri.encode(photoUri.toString())}&prompt=${Uri.encode(prompt)}")
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onSignIn = { nav.navigate("signin") },
                onPrivacy = { nav.navigate("privacy") },
                onAbout = { nav.navigate("about") },
                onUpdateFound = { pendingUpdate = it }
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
                },
                onBatch = { uris ->
                    val photos = uris.joinToString(",") { Uri.encode(it.toString()) }
                    nav.navigate("batchrun/${Uri.encode(code)}?photos=${Uri.encode(photos)}")
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
                },
                onSignIn = { nav.navigate("signin") }
            )
        }
        composable(
            "runcustom?photo={photo}&prompt={prompt}",
            arguments = listOf(
                navArgument("photo") { type = NavType.StringType },
                navArgument("prompt") { type = NavType.StringType }
            )
        ) { entry ->
            val photo = Uri.decode(entry.arguments?.getString("photo").orEmpty())
            val prompt = Uri.decode(entry.arguments?.getString("prompt").orEmpty())
            RunScreen(
                code = "__custom__",
                photoUriString = photo,
                customPrompt = prompt,
                onBack = { nav.popBackStack() },
                onDone = { recordId ->
                    nav.navigate("result/$recordId") { popUpTo("gallery") }
                },
                onSignIn = { nav.navigate("signin") }
            )
        }
        composable(
            "batchrun/{code}?photos={photos}",
            arguments = listOf(
                navArgument("code") { type = NavType.StringType },
                navArgument("photos") { type = NavType.StringType }
            )
        ) { entry ->
            val code = Uri.decode(entry.arguments?.getString("code").orEmpty())
            val photos = Uri.decode(entry.arguments?.getString("photos").orEmpty())
                .split(",").filter { it.isNotEmpty() }
            BatchRunScreen(
                code = code,
                photoUriStrings = photos,
                onBack = { nav.popBackStack() },
                onDone = { nav.navigate("history") { popUpTo("gallery") } }
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

}
