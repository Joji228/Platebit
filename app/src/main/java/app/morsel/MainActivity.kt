package app.morsel

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import app.morsel.ui.theme.AccentColor
import app.morsel.ui.body.BodyScreen
import app.morsel.ui.scale.AutoWeighIn
import app.morsel.ui.scan.HardwareShutter
import app.morsel.ui.body.BodyViewModel
import app.morsel.ui.coach.CoachHint
import app.morsel.ui.coach.CoachPullBubble
import app.morsel.ui.coach.CoachScreen
import app.morsel.ui.coach.CoachViewModel
import app.morsel.ui.coach.coachArmTick
import app.morsel.ui.coach.coachPopTap
import app.morsel.ui.coach.coachPull
import app.morsel.ui.coach.rememberCoachPull
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.morsel.ui.dashboard.DashboardScreen
import app.morsel.ui.dashboard.DashboardViewModel
import app.morsel.ui.onboarding.OnboardingScreen
import app.morsel.ui.onboarding.OnboardingViewModel
import app.morsel.ui.result.ResultScreen
import app.morsel.ui.result.ResultViewModel
import app.morsel.ui.scan.ScanScreen
import app.morsel.ui.scan.ScanViewModel
import app.morsel.ui.settings.AiSettingsScreen
import app.morsel.ui.settings.SettingsScreen
import app.morsel.ui.settings.SettingsViewModel
import app.morsel.ui.theme.MorselTheme
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object ScanRoute
@Serializable data class ResultRoute(val mealId: Long = -1L)
@Serializable object SettingsRoute
@Serializable object AiSettingsRoute
@Serializable data class OnboardingRoute(val edit: Boolean = false)
@Serializable object BodyRoute

class MainActivity : ComponentActivity() {
    private var ready = false

    /** On the Scan screen either volume key snaps and analyzes; anywhere else they change the volume. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shutter = HardwareShutter.onPress
        if (shutter != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.repeatCount == 0) shutter() // holding the key doesn't fire again
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (HardwareShutter.onPress != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // Coming back after the phone slept through midnight: update "today" straight away.
        (application as MorselApp).container.clock.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        val container = (application as MorselApp).container

        setContent {
            val themeMode by container.settings.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val accent by container.settings.accent.collectAsStateWithLifecycle(initialValue = AccentColor.Default)
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
                // Let the saved accent land before the splash goes away, so it never flashes lime first.
                container.settings.accent.first()
                ready = true
            }

            MorselTheme(dark = dark, accent = accent) {
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

    // The AI coach (beta): pulled down from the top of any screen except Settings and setup.
    val coachOn by container.settings.coachEnabled.collectAsStateWithLifecycle(initialValue = false)
    val coachTutorialDone by container.settings.coachTutorialDone.collectAsStateWithLifecycle(initialValue = true)
    var coachOpen by rememberSaveable { mutableStateOf(false) }
    val coachHere = onToday || onScan || destination?.hasRoute<BodyRoute>() == true || destination?.hasRoute<ResultRoute>() == true
    LaunchedEffect(coachOn) { if (!coachOn) coachOpen = false }
    val pull = rememberCoachPull()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .coachPull(
                pull,
                enabled = coachOn && coachHere && !coachOpen,
                onArm = { view.coachArmTick() },
                onPop = {
                    view.coachPopTap()
                    // A beat for the pop to show before the chat slides down over it.
                    scope.launch {
                        delay(40)
                        coachOpen = true
                    }
                    if (!coachTutorialDone) scope.launch { container.settings.setCoachTutorialDone() }
                },
            ),
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                AnimatedVisibility(
                    visible = onToday || onScan,
                    enter = slideInVertically(tween(MOTION_MS, easing = MotionEasing)) { it } + fadeIn(tween(MOTION_MS)),
                    exit = slideOutVertically(tween(MOTION_MS, easing = MotionEasing)) { it } + fadeOut(tween(MOTION_MS)),
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
                // iOS-style push/pop: the new page slides over, the old one drifts aside and dims.
                // Tabs slide side by side like pages. Everything moves horizontally, nothing scales,
                // and every transition shares one duration and curve so they all feel alike.
                enterTransition = { if (isTabSwitch()) tabEnter() else pushEnter() },
                exitTransition = { if (isTabSwitch()) tabExit() else pushExit() },
                popEnterTransition = { if (isTabSwitch()) tabEnter() else popEnter() },
                popExitTransition = { if (isTabSwitch()) tabExit() else popExit() },
                // The back swipe gesture uses these instead of the pop ones. It plays the exact same
                // animation as the matching button press, so Scan → Today swipes like a tab and
                // every other screen slides away left to right. Linear timing keeps the page locked
                // to the finger; letting go finishes the slide.
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
                            onOpenBody = { nav.navigate(BodyRoute) },
                        )
                    }
                }
                composable<BodyRoute> {
                    Screen {
                        val vm = viewModel { BodyViewModel(container) }
                        BodyScreen(vm, onBack = { nav.popBackStack() })
                    }
                }
                composable<ScanRoute> {
                    Screen(bottom = barSpace) {
                        val vm = viewModel { ScanViewModel(container) }
                        ScanScreen(vm, onResult = { nav.navigate(ResultRoute()) }, onToday = { nav.switchTab(TodayRoute) })
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
                            onAiEngine = { nav.navigate(AiSettingsRoute) },
                            onReset = { nav.navigate(OnboardingRoute()) { popUpTo(0) { inclusive = true } } },
                        )
                    }
                }
                composable<AiSettingsRoute> {
                    Screen {
                        val vm = viewModel { SettingsViewModel(container) }
                        AiSettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
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
            // Drawn over every screen: the smart scale's live weight and saved weigh-ins.
            AutoWeighIn(container, onOpenBody = { nav.navigate(BodyRoute) { launchSingleTop = true } })
        }

        if (coachOn && !coachTutorialDone && coachHere && !coachOpen && !pull.popped) {
            CoachHint(onSkip = { scope.launch { container.settings.setCoachTutorialDone() } })
        }
        AnimatedVisibility(
            visible = coachOpen,
            enter = slideInVertically(tween(260, easing = MotionEasing)) { -it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(MOTION_MS, easing = MotionEasing)) { -it } + fadeOut(tween(MOTION_MS)),
        ) {
            BackHandler { coachOpen = false }
            // A Surface, not a plain Box: the chat sits above the Scaffold, so it has to provide the
            // theme's text colour itself (without it, plain text falls back to black).
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val vm = viewModel { CoachViewModel(container) }
                CoachScreen(vm, onBack = { coachOpen = false })
            }
        }
        CoachPullBubble(pull, Modifier.align(Alignment.TopCenter))
    }
}

/** One duration and curve for every screen change: quick, with a soft landing. */
private const val MOTION_MS = 280
private val MotionEasing = FastOutSlowInEasing
private val MotionSpec = tween<IntOffset>(MOTION_MS, easing = MotionEasing)
private val GestureSpec = tween<IntOffset>(MOTION_MS, easing = LinearEasing)

/** Opening a screen: it slides in from the right while the old one drifts left and dims. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter() = slideIntoContainer(SlideDirection.Start, MotionSpec)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit() =
    slideOutOfContainer(SlideDirection.Start, MotionSpec) { it / 4 } + fadeOut(tween(MOTION_MS), targetAlpha = 0.6f)

/** Going back, always left to right: the top screen slides off to the right and the one below returns. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(spec: FiniteAnimationSpec<IntOffset> = MotionSpec) =
    slideIntoContainer(SlideDirection.End, spec) { it / 4 } + fadeIn(tween(MOTION_MS, easing = LinearEasing), initialAlpha = 0.6f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(spec: FiniteAnimationSpec<IntOffset> = MotionSpec) =
    slideOutOfContainer(SlideDirection.End, spec)

/**
 * Today and Scan are tabs, so they slide side by side like pages: both move together with no
 * dimming or shadow. Scan sits to the right of Today.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.towardScan() = targetState.destination.hasRoute<ScanRoute>()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter(spec: FiniteAnimationSpec<IntOffset> = MotionSpec) =
    slideIntoContainer(if (towardScan()) SlideDirection.Start else SlideDirection.End, spec)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit(spec: FiniteAnimationSpec<IntOffset> = MotionSpec) =
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
