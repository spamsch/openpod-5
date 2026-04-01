package com.openpod.feature.onboarding.permissions

import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject

/**
 * UI state for the Permissions onboarding screen.
 *
 * @property bluetoothGranted Whether BLE permissions have been granted.
 * @property locationGranted Whether location permission has been granted.
 * @property notificationsGranted Whether notification permission has been granted.
 */
data class PermissionsState(
    val bluetoothGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
) : UiState {
    /** Continue button is enabled when all required permissions are granted. */
    val canContinue: Boolean
        get() = bluetoothGranted && locationGranted
}

/** User intents for the Permissions screen. */
sealed interface PermissionsIntent : UiIntent {
    /** Update the current permission states after a check or grant result. */
    data class UpdatePermissions(
        val bluetoothGranted: Boolean,
        val locationGranted: Boolean,
        val notificationsGranted: Boolean,
    ) : PermissionsIntent

    /** User tapped "Grant Bluetooth". */
    data object RequestBluetooth : PermissionsIntent

    /** User tapped "Grant Location". */
    data object RequestLocation : PermissionsIntent

    /** User tapped "Grant Notifications". */
    data object RequestNotifications : PermissionsIntent

    /** User tapped "Continue". */
    data object Continue : PermissionsIntent
}

/** One-shot effects for the Permissions screen. */
sealed interface PermissionsEffect : UiEffect {
    /** Launch the system permission dialog for Bluetooth. */
    data object LaunchBluetoothPermission : PermissionsEffect

    /** Launch the system permission dialog for Location. */
    data object LaunchLocationPermission : PermissionsEffect

    /** Launch the system permission dialog for Notifications. */
    data object LaunchNotificationPermission : PermissionsEffect

    /** Open the system app settings (for permanently denied permissions). */
    data object OpenAppSettings : PermissionsEffect

    /** Navigate forward to Profile screen. */
    data object NavigateToProfile : PermissionsEffect

    /** Show a snackbar about denied permission with settings action. */
    data class ShowDeniedSnackbar(val permissionName: String) : PermissionsEffect
}

/**
 * ViewModel for the Permissions onboarding screen.
 *
 * Manages the state of three permission groups (Bluetooth, Location, Notifications)
 * and emits effects to trigger system permission dialogs or navigation.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor() : MviViewModel<PermissionsState, PermissionsIntent, PermissionsEffect>(
    initialState = PermissionsState(),
) {
    override fun handleIntent(intent: PermissionsIntent) {
        when (intent) {
            is PermissionsIntent.UpdatePermissions -> {
                Timber.d(
                    "Permissions updated: BLE=%s, Location=%s, Notifications=%s",
                    intent.bluetoothGranted,
                    intent.locationGranted,
                    intent.notificationsGranted,
                )
                updateState {
                    copy(
                        bluetoothGranted = intent.bluetoothGranted,
                        locationGranted = intent.locationGranted,
                        notificationsGranted = intent.notificationsGranted,
                    )
                }
            }

            PermissionsIntent.RequestBluetooth -> {
                emitEffect(PermissionsEffect.LaunchBluetoothPermission)
            }

            PermissionsIntent.RequestLocation -> {
                emitEffect(PermissionsEffect.LaunchLocationPermission)
            }

            PermissionsIntent.RequestNotifications -> {
                emitEffect(PermissionsEffect.LaunchNotificationPermission)
            }

            PermissionsIntent.Continue -> {
                if (currentState.canContinue) {
                    Timber.i("All required permissions granted, proceeding to profile setup")
                    emitEffect(PermissionsEffect.NavigateToProfile)
                }
            }
        }
    }
}
