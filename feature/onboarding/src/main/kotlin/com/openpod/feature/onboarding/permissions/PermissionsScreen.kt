package com.openpod.feature.onboarding.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.feature.onboarding.R
import timber.log.Timber

/**
 * Permissions screen that requests BLE, Location, and Notification permissions.
 *
 * Shows explanation cards for each permission with grant buttons that transition
 * to "Granted" state. The "Continue" button is enabled only when all required
 * permissions (Bluetooth + Location) are granted.
 *
 * @param onContinue Callback when the user taps "Continue" with all permissions granted.
 * @param onBack Callback when the user navigates back.
 * @param modifier Modifier applied to the root layout.
 * @param viewModel The [PermissionsViewModel] managing screen state.
 */
@Composable
fun PermissionsScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Check permissions on resume (covers returning from Settings)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            checkAndUpdatePermissions(context, viewModel)
        }
    }

    // Permission launchers
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        Timber.d("Bluetooth permission result: %s", results)
        checkAndUpdatePermissions(context, viewModel)
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Timber.d("Location permission result: %s", granted)
        checkAndUpdatePermissions(context, viewModel)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Timber.d("Notification permission result: %s", granted)
        checkAndUpdatePermissions(context, viewModel)
    }

    val deniedMessage = stringResource(R.string.onboarding_permissions_denied_snackbar)
    val openSettingsLabel = stringResource(R.string.onboarding_permissions_open_settings)

    // Handle effects
    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            PermissionsEffect.LaunchBluetoothPermission -> {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                } else {
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                    )
                }
                bluetoothLauncher.launch(permissions)
            }

            PermissionsEffect.LaunchLocationPermission -> {
                locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            PermissionsEffect.LaunchNotificationPermission -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            PermissionsEffect.OpenAppSettings -> {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            }

            PermissionsEffect.NavigateToProfile -> onContinue()

            is PermissionsEffect.ShowDeniedSnackbar -> {
                val result = snackbarHostState.showSnackbar(
                    message = deniedMessage,
                    actionLabel = openSettingsLabel,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    context.startActivity(intent)
                }
            }
        }
    }

    val continueDisabledA11y = stringResource(R.string.onboarding_permissions_continue_disabled_a11y)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_permissions_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Permission cards (scrollable)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PermissionCard(
                icon = Icons.Filled.Bluetooth,
                title = stringResource(R.string.onboarding_permissions_bluetooth_title),
                description = stringResource(R.string.onboarding_permissions_bluetooth_description),
                granted = state.bluetoothGranted,
                grantButtonText = stringResource(R.string.onboarding_permissions_grant_bluetooth),
                grantButtonA11y = stringResource(R.string.onboarding_permissions_grant_bluetooth_a11y),
                grantedA11y = stringResource(
                    R.string.onboarding_permissions_granted_a11y,
                    stringResource(R.string.onboarding_permissions_bluetooth_title),
                ),
                onGrant = { viewModel.onIntent(PermissionsIntent.RequestBluetooth) },
            )

            PermissionCard(
                icon = Icons.Filled.LocationOn,
                title = stringResource(R.string.onboarding_permissions_location_title),
                description = stringResource(R.string.onboarding_permissions_location_description),
                granted = state.locationGranted,
                grantButtonText = stringResource(R.string.onboarding_permissions_grant_location),
                grantButtonA11y = stringResource(R.string.onboarding_permissions_grant_location_a11y),
                grantedA11y = stringResource(
                    R.string.onboarding_permissions_granted_a11y,
                    stringResource(R.string.onboarding_permissions_location_title),
                ),
                onGrant = { viewModel.onIntent(PermissionsIntent.RequestLocation) },
            )

            PermissionCard(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.onboarding_permissions_notifications_title),
                description = stringResource(R.string.onboarding_permissions_notifications_description),
                granted = state.notificationsGranted,
                grantButtonText = stringResource(R.string.onboarding_permissions_grant_notifications),
                grantButtonA11y = stringResource(R.string.onboarding_permissions_grant_notifications_a11y),
                grantedA11y = stringResource(
                    R.string.onboarding_permissions_granted_a11y,
                    stringResource(R.string.onboarding_permissions_notifications_title),
                ),
                onGrant = { viewModel.onIntent(PermissionsIntent.RequestNotifications) },
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Snackbar host
        SnackbarHost(hostState = snackbarHostState)

        // Continue button
        Button(
            onClick = { viewModel.onIntent(PermissionsIntent.Continue) },
            enabled = state.canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics {
                    if (!state.canContinue) {
                        contentDescription = continueDisabledA11y
                    }
                },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_permissions_continue),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * A single permission card with icon, title, description, and grant/granted state.
 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    grantButtonText: String,
    grantButtonA11y: String,
    grantedA11y: String,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grantedText = stringResource(R.string.onboarding_permissions_granted)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon in circle
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    )
                    .padding(8.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (granted) {
            // Granted indicator
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics { contentDescription = grantedA11y },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    disabledContentColor = com.openpod.core.ui.theme.GlucoseInRange,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = grantedText, style = MaterialTheme.typography.titleSmall)
            }
        } else {
            // Grant button
            Button(
                onClick = onGrant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics { contentDescription = grantButtonA11y },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(text = grantButtonText, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Check all permissions and update the ViewModel state. */
private fun checkAndUpdatePermissions(context: Context, viewModel: PermissionsViewModel) {
    val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
            PackageManager.PERMISSION_GRANTED
    }

    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true // Auto-granted on older APIs
    }

    viewModel.onIntent(
        PermissionsIntent.UpdatePermissions(
            bluetoothGranted = bluetoothGranted,
            locationGranted = locationGranted,
            notificationsGranted = notificationsGranted,
        ),
    )
}
