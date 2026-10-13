package app.photopromptcodes

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.photopromptcodes.ui.CodeDetailScreen
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
import app.photopromptcodes.ui.theme.PhotoPromptCodesTheme

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

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val start = remember {
        when {
            !isOnboarded(ctx) -> "onboarding"
            !isLoggedInFlag(ctx) -> "login"
            else -> "gallery"
        }
    }
    NavHost(navController = nav, startDestination = start) {
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
                onOpenCode = { code -> nav.navigate("detail/${Uri.encode(code)}") },
                onOpenHistory = { nav.navigate("history") },
                onOpenSettings = { nav.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onSignIn = { nav.navigate("signin") },
                onPrivacy = { nav.navigate("privacy") }
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
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("result/$id") }
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
