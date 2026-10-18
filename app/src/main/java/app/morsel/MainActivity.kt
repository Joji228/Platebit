package app.morsel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.morsel.data.ThemeMode
import app.morsel.ui.dashboard.DashboardScreen
import app.morsel.ui.dashboard.DashboardViewModel
import app.morsel.ui.onboarding.OnboardingScreen
import app.morsel.ui.onboarding.OnboardingViewModel
import app.morsel.ui.result.ResultScreen
import app.morsel.ui.result.ResultViewModel
import app.morsel.ui.scan.ScanScreen
import app.morsel.ui.scan.ScanViewModel
import app.morsel.ui.settings.SettingsScreen
import app.morsel.ui.settings.SettingsViewModel
import app.morsel.ui.theme.MorselTheme
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object ScanRoute
@Serializable data class ResultRoute(val mealId: Long = -1L)
@Serializable object SettingsRoute
@Serializable data class OnboardingRoute(val edit: Boolean = false)

class MainActivity : ComponentActivity() {
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        val container = (application as MorselApp).container

        setContent {
            val themeMode by container.settings.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.Transparent.toArgb())
                } else {
                    SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            var start by remember { mutableStateOf<Any?>(null) }
            LaunchedEffect(Unit) {
                start = if (container.settings.profile.first() == null) OnboardingRoute() else TodayRoute
                ready = true
            }

            MorselTheme(dark = dark) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    start?.let { MorselNavHost(container, it) }
                }
            }
        }
    }
}

@Composable
private fun MorselNavHost(container: AppContainer, start: Any) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onToday = destination?.hasRoute<TodayRoute>() == true
    val onScan = destination?.hasRoute<ScanRoute>() == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = onToday || onScan,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                BottomBar(
                    scanSelected = onScan,
                    onToday = { nav.switchTab(TodayRoute) },
                    onScan = { nav.switchTab(ScanRoute) },
                )
            }
        },
    ) { _ ->
        // The bar floats over the tab screens, which reserve a fixed space for it; padding the
        // whole NavHost instead would make pushed pages jump while the bar animates away.
        val barSpace = BAR_HEIGHT + 0.5.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        NavHost(
            navController = nav,
            startDestination = start,
            // iOS-style push/pop: the new page slides over, the old one drifts left and dims.
            // Tabs slide side by side like pages. Everything moves horizontally, nothing scales.
            enterTransition = { if (isTabSwitch()) tabEnter() else pushEnter(PushSpec) },
            exitTransition = { if (isTabSwitch()) tabExit() else pushExit(PushSpec) },
            popEnterTransition = { if (isTabSwitch()) tabEnter() else popEnter(PushSpec) },
            popExitTransition = { if (isTabSwitch()) tabExit() else popExit(PushSpec) },
            // The back swipe gesture uses these instead of the pop ones. Left unset, Navigation
            // shrinks the page and fades it, which looked cut off mid-way. Linear timing makes
            // the page track the finger exactly; letting go finishes the same slide.
            predictivePopEnterTransition = { _ -> if (isTabSwitch()) tabEnter(GestureSpec) else popEnter(GestureSpec) },
            predictivePopExitTransition = { _ -> if (isTabSwitch()) tabExit(GestureSpec) else popExit(GestureSpec) },
        ) {
            composable<TodayRoute> {
                Screen(bottom = barSpace) {
                    val vm = viewModel { DashboardViewModel(container) }
                    DashboardScreen(
                        viewModel = vm,
                        onScan = { nav.switchTab(ScanRoute) },
                        onOpenMeal = { nav.navigate(ResultRoute(it)) },
                        onSettings = { nav.navigate(SettingsRoute) },
                    )
                }
            }
            composable<ScanRoute> {
                Screen(bottom = barSpace) {
                    val vm = viewModel { ScanViewModel(container) }
                    ScanScreen(vm, onResult = { nav.navigate(ResultRoute()) })
                }
            }
            composable<ResultRoute> { backStack ->
                val route = backStack.toRoute<ResultRoute>()
                Screen {
                    val vm = viewModel { ResultViewModel(container, route.mealId) }
                    ResultScreen(
                        viewModel = vm,
                        onClose = { nav.popBackStack() },
                        onSaved = {
                            // Pop back so the dashboard slides in from behind, like closing a sheet.
                            if (!nav.popBackStack<TodayRoute>(inclusive = false)) {
                                nav.navigate(TodayRoute) { popUpTo(0) { inclusive = true } }
                            }
                        },
                    )
                }
            }
            composable<SettingsRoute> {
                Screen {
                    val vm = viewModel { SettingsViewModel(container) }
                    SettingsScreen(
                        viewModel = vm,
                        onBack = { nav.popBackStack() },
                        onEditProfile = { nav.navigate(OnboardingRoute(edit = true)) },
                        onReset = { nav.navigate(OnboardingRoute()) { popUpTo(0) { inclusive = true } } },
                    )
                }
            }
            composable<OnboardingRoute> { backStack ->
                val route = backStack.toRoute<OnboardingRoute>()
                Screen {
                    val vm = viewModel { OnboardingViewModel(container, route.edit) }
                    OnboardingScreen(
                        viewModel = vm,
                        onDone = {
                            if (route.edit) {
                                nav.popBackStack()
                            } else {
                                nav.navigate(TodayRoute) { popUpTo(0) { inclusive = true } }
                            }
                        },
                        onExit = { if (route.edit) nav.popBackStack() },
                    )
                }
            }
        }
    }
}

private const val PUSH_MS = 380
private val PushEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
private val PushSpec = tween<IntOffset>(PUSH_MS, easing = PushEasing)
private val GestureSpec = tween<IntOffset>(PUSH_MS, easing = LinearEasing)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(spec: FiniteAnimationSpec<IntOffset>) =
    slideIntoContainer(SlideDirection.Start, spec)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(spec: FiniteAnimationSpec<IntOffset>) =
    slideOutOfContainer(SlideDirection.Start, spec) { it / 4 } + fadeOut(tween(PUSH_MS, easing = LinearEasing), targetAlpha = 0.6f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(spec: FiniteAnimationSpec<IntOffset>) =
    slideIntoContainer(SlideDirection.End, spec) { it / 4 } + fadeIn(tween(PUSH_MS, easing = LinearEasing), initialAlpha = 0.6f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(spec: FiniteAnimationSpec<IntOffset>) =
    slideOutOfContainer(SlideDirection.End, spec)

/** Scan sits to the right of Today, so going to Scan slides left and coming back slides right. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.towardScan() = targetState.destination.hasRoute<ScanRoute>()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter(spec: FiniteAnimationSpec<IntOffset> = PushSpec) =
    slideIntoContainer(if (towardScan()) SlideDirection.Start else SlideDirection.End, spec)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit(spec: FiniteAnimationSpec<IntOffset> = PushSpec) =
    slideOutOfContainer(if (towardScan()) SlideDirection.Start else SlideDirection.End, spec)

private fun NavDestination?.isTab() = this?.hasRoute<TodayRoute>() == true || this?.hasRoute<ScanRoute>() == true

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.isTab() && targetState.destination.isTab()

/** Every page paints its own background so two pages never show through each other mid-slide. */
@Composable
private fun Screen(bottom: Dp = 0.dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = bottom),
    ) { content() }
}

private val BAR_HEIGHT = 68.dp

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(TodayRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(scanSelected: Boolean, onToday: () -> Unit, onScan: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(BAR_HEIGHT)
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarItem(Icons.Rounded.SpaceDashboard, "Today", !scanSelected, onToday)
                BarItem(Icons.Rounded.CenterFocusStrong, "Scan", scanSelected, onScan)
            }
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
