package com.openpod.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openpod.core.datastore.AppDataResetter
import com.openpod.core.datastore.OpenPodPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the root navigation host.
 *
 * Observes the onboarding completion state to determine whether
 * the app should start at the onboarding flow or the main dashboard.
 * The state is `null` until the first preference read completes,
 * preventing a flash of the wrong start screen.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    preferences: OpenPodPreferences,
    val appDataResetter: AppDataResetter,
) : ViewModel() {

    /**
     * Whether onboarding has been completed.
     *
     * - `null` — still loading (show nothing)
     * - `false` — show onboarding
     * - `true` — show dashboard
     */
    val isOnboardingComplete: StateFlow<Boolean?> = preferences.isOnboardingComplete()
        .map { complete ->
            Timber.d("Navigation: onboarding complete = %s", complete)
            complete
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
}
