package com.openpod.feature.onboarding.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.openpod.feature.onboarding.permissions.PermissionsScreen
import com.openpod.feature.onboarding.pin.PinScreen
import com.openpod.feature.onboarding.profile.ProfileScreen
import com.openpod.feature.onboarding.ready.ReadyScreen
import com.openpod.feature.onboarding.welcome.WelcomeScreen

/** Navigation route for the onboarding graph root. */
const val ONBOARDING_GRAPH_ROUTE = "onboarding_graph"

/** Route constants for each onboarding screen. */
object OnboardingRoute {
    const val WELCOME = "onboarding/welcome"
    const val PERMISSIONS = "onboarding/permissions"
    const val PROFILE = "onboarding/profile"
    const val PIN = "onboarding/pin"
    const val READY = "onboarding/ready"
}

/**
 * Registers the onboarding navigation graph with all five screens.
 *
 * The graph uses a linear flow: Welcome -> Permissions -> Profile -> PIN -> Ready.
 * The Ready screen's "Pair Your First Pod" button triggers [onOnboardingComplete],
 * which should mark onboarding as finished and navigate to the main app.
 *
 * @param navController The [NavController] managing navigation within this graph.
 * @param onOnboardingComplete Callback invoked when the user completes onboarding
 *   by tapping "Pair Your First Pod" on the Ready screen.
 */
fun NavGraphBuilder.onboardingNavGraph(
    navController: NavController,
    onOnboardingComplete: () -> Unit,
) {
    navigation(
        startDestination = OnboardingRoute.WELCOME,
        route = ONBOARDING_GRAPH_ROUTE,
    ) {
        composable(OnboardingRoute.WELCOME) {
            WelcomeScreen(
                onGetStarted = {
                    navController.navigate(OnboardingRoute.PERMISSIONS)
                },
            )
        }

        composable(OnboardingRoute.PERMISSIONS) {
            PermissionsScreen(
                onContinue = {
                    navController.navigate(OnboardingRoute.PROFILE)
                },
                onBack = {
                    navController.popBackStack()
                },
            )
        }

        composable(OnboardingRoute.PROFILE) {
            ProfileScreen(
                onComplete = {
                    navController.navigate(OnboardingRoute.PIN)
                },
                onBack = {
                    navController.popBackStack()
                },
            )
        }

        composable(OnboardingRoute.PIN) {
            PinScreen(
                onComplete = {
                    navController.navigate(OnboardingRoute.READY)
                },
                onBack = {
                    navController.popBackStack()
                },
            )
        }

        composable(OnboardingRoute.READY) {
            ReadyScreen(
                onPairPod = onOnboardingComplete,
                onEditProfile = { subStep ->
                    navController.navigate(OnboardingRoute.PROFILE)
                },
                onEditPin = {
                    navController.navigate(OnboardingRoute.PIN)
                },
                onBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}

/**
 * Navigate to the onboarding graph, clearing the back stack.
 *
 * @param navOptions Optional navigation options.
 */
fun NavController.navigateToOnboarding(navOptions: NavOptions? = null) {
    navigate(ONBOARDING_GRAPH_ROUTE, navOptions)
}
