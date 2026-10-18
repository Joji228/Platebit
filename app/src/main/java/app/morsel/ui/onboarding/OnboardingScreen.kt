package app.morsel.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.R
import app.morsel.domain.Activity
import app.morsel.domain.Nutrition
import app.morsel.domain.Sex
import app.morsel.ui.components.MorselCard
import app.morsel.ui.components.NumberStepper
import app.morsel.ui.components.PillSelector
import app.morsel.ui.components.PrimaryButton
import app.morsel.ui.components.ProgressLine
import app.morsel.ui.components.RoundIconButton
import app.morsel.ui.components.format
import app.morsel.ui.theme.Numeric
import app.morsel.ui.theme.Nutrient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { if (!viewModel.back()) onExit() }

    val steps = state.steps
    val index = steps.indexOf(state.step).coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        if (state.step != Step.WELCOME) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { if (!viewModel.back()) onExit() })
                Spacer(Modifier.width(16.dp))
                ProgressLine((index + 1f) / steps.size, MaterialTheme.colorScheme.primary, Modifier.weight(1f), height = 4.dp)
                Spacer(Modifier.width(56.dp))
            }
        }

        AnimatedContent(
            targetState = state.step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(280)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(280))) togetherWith
                    (slideOutHorizontally(tween(200)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
            },
            label = "step",
        ) { step ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                when (step) {
                    Step.WELCOME -> Welcome()
                    Step.BASICS -> Basics(state, viewModel)
                    Step.WEIGHT -> Weight(state, viewModel)
                    Step.ACTIVITY -> ActivityStep(state, viewModel)
                    Step.PACE -> Pace(state, viewModel)
                    Step.PLAN -> Plan(state)
                }
            }
        }

        PrimaryButton(
            text = when (state.step) {
                Step.WELCOME -> "Get started"
                Step.PLAN -> if (state.edit) "Save" else "Start tracking"
                else -> "Continue"
            },
            onClick = { if (state.step == Step.PLAN) viewModel.finish(onDone) else viewModel.next() },
            enabled = state.loaded,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(96.dp))
    Box(
        Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(150.dp))
    }
    Spacer(Modifier.height(32.dp))
    Text("Welcome to Platebit", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "Snap a photo of your meal and Platebit estimates the calories and macros for you.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "First, a few quick questions to set your daily target. It takes about 30 seconds.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Basics(state: OnboardingState, vm: OnboardingViewModel) {
    Heading("About you", "Used to estimate how much energy your body burns. Tap a number to type it.")
    PillSelector(
        listOf(Sex.MALE, Sex.FEMALE),
        state.profile.sex,
        { if (it == Sex.MALE) "Male" else "Female" },
        { sex -> vm.update { it.copy(sex = sex) } },
    )
    Spacer(Modifier.height(12.dp))
    NumberStepper("Age", state.profile.age.toDouble(), { v -> vm.update { it.copy(age = v.toInt()) } }, 14.0..90.0, 1.0, "years")
    Spacer(Modifier.height(12.dp))
    NumberStepper("Height", state.profile.heightCm, { v -> vm.update { it.copy(heightCm = v) } }, 130.0..220.0, 1.0, "cm")
}

@Composable
private fun Weight(state: OnboardingState, vm: OnboardingViewModel) {
    Heading("Your weight", "Where you are now and where you want to be. Tap a number to type it.")
    if (state.edit) {
        NumberStepper(
            "Starting weight",
            state.profile.startWeightKg,
            { v -> vm.update { it.copy(startWeightKg = v) } },
            35.0..250.0,
            0.1,
            "kg",
            decimals = 1,
        )
        Spacer(Modifier.height(12.dp))
    }
    NumberStepper("Current weight", state.currentWeight, vm::setCurrentWeight, 35.0..250.0, 0.1, "kg", decimals = 1)
    Spacer(Modifier.height(12.dp))
    NumberStepper(
        "Goal weight",
        state.profile.goalWeightKg,
        { v -> vm.update { it.copy(goalWeightKg = v) } },
        35.0..250.0,
        0.1,
        "kg",
        decimals = 1,
    )
}

@Composable
private fun ActivityStep(state: OnboardingState, vm: OnboardingViewModel) {
    Heading("How active are you?", "Count workouts and how much you move during the day.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Activity.entries.forEach { activity ->
            SelectableCard(
                title = activity.title,
                subtitle = activity.subtitle,
                selected = state.profile.activity == activity,
                onClick = { vm.update { it.copy(activity = activity) } },
            )
        }
    }
}

@Composable
private fun Pace(state: OnboardingState, vm: OnboardingViewModel) {
    val losing = state.profile.goalWeightKg < state.currentWeight
    Heading(
        if (losing) "How fast?" else "How fast do you want to gain?",
        if (losing) "Slower is easier to stick with and keeps more muscle." else "Slow gains keep fat gain low.",
    )
    val options = if (losing) {
        listOf(0.25 to "Relaxed", 0.5 to "Steady", 0.75 to "Fast", 1.0 to "Aggressive")
    } else {
        listOf(0.1 to "Lean", 0.25 to "Steady", 0.5 to "Fast")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (rate, label) ->
            SelectableCard(
                title = label,
                subtitle = "${rate.format(2).trimEnd('0').trimEnd('.', ',')} kg per week",
                selected = state.profile.weeklyRateKg == rate,
                onClick = { vm.update { it.copy(weeklyRateKg = rate) } },
            )
        }
    }
}

@Composable
private fun Plan(state: OnboardingState) {
    val targets = state.targets
    Heading("Your daily plan", "You can change any of this later in Settings.")
    MorselCard {
        Text("Daily calories", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(targets.kcal.format(), style = MaterialTheme.typography.displayMedium.merge(Numeric), color = MaterialTheme.colorScheme.primary)
            Text(" kcal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row {
            PlanMacro("Protein", targets.protein, Nutrient.Protein, Modifier.weight(1f))
            PlanMacro("Carbs", targets.carbs, Nutrient.Carbs, Modifier.weight(1f))
            PlanMacro("Fat", targets.fat, Nutrient.Fat, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(12.dp))
    MorselCard {
        PlanLine("BMI", "${targets.bmi.format(1)} · ${Nutrition.bmiCategory(targets.bmi)}")
        PlanLine("Resting burn (BMR)", "${targets.bmr.format()} kcal")
        PlanLine("Daily burn with activity", "${targets.tdee.format()} kcal")
        PlanLine(
            if (targets.dailyDelta <= 0) "Daily deficit" else "Daily surplus",
            "${kotlin.math.abs(targets.dailyDelta).format()} kcal",
        )
        targets.weeksToGoal?.let { weeks ->
            val date = LocalDate.now().plusDays((weeks * 7).roundToLong())
            PlanLine("Goal reached around", date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())))
        }
        PlanLine("Water goal", "${(targets.waterMl / 1000.0).format(2).trimEnd('0').trimEnd('.', ',')} L a day")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Calories use the Mifflin-St Jeor equation; water uses your weight and activity. Estimates only, not medical advice.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PlanMacro(label: String, grams: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Text("$grams g", style = MaterialTheme.typography.titleLarge.merge(Numeric))
    }
}

@Composable
private fun PlanLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Numeric))
    }
}

@Composable
private fun SelectableCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(20.dp)),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
