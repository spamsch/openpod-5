package com.openpod.core.ui.mvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * Collect one-shot effects from an [MviViewModel] in a Compose-safe way.
 *
 * Effects are consumed exactly once and not replayed on recomposition.
 * Use this for navigation events, toasts, haptic triggers, etc.
 *
 * ## Usage
 *
 * ```kotlin
 * @Composable
 * fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
 *     CollectEffects(viewModel.effect) { effect ->
 *         when (effect) {
 *             is DashboardEffect.OpenBolus -> navigator.navigateToBolus()
 *             is DashboardEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
 *         }
 *     }
 *     // ... rest of screen
 * }
 * ```
 */
@Composable
fun <E : UiEffect> CollectEffects(
    effects: Flow<E>,
    onEffect: suspend (E) -> Unit,
) {
    LaunchedEffect(Unit) {
        effects.onEach(onEffect).collect()
    }
}
