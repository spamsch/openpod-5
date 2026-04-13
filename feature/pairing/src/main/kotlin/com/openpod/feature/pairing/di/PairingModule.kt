package com.openpod.feature.pairing.di

import com.openpod.core.crypto.CryptoManager
import com.openpod.feature.pairing.BuildConfig
import com.openpod.feature.pairing.domain.BlePodManager
import com.openpod.feature.pairing.domain.EmulatorPodManager
import com.openpod.feature.pairing.domain.FakePodManager
import com.openpod.domain.pod.PodManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

/**
 * Hilt module that provides [PodManager] as a singleton.
 *
 * Singleton-scoped so the connection survives navigation from
 * pairing to dashboard. Switches between:
 * - [BlePodManager]: Real BLE with TWICommand + text RHP.
 *   Build with: `./gradlew :app:installDebug -PuseBle=true`
 * - [EmulatorPodManager]: TCP to Python emulator with real crypto.
 *   Build with: `./gradlew :app:installDebug -PuseEmulator=true`
 * - [FakePodManager]: Simulated data for UI development (default).
 */
@Module
@InstallIn(SingletonComponent::class)
object PairingModule {

    @Provides
    @Singleton
    fun providePodManager(
        cryptoManager: CryptoManager,
    ): PodManager {
        return when {
            BuildConfig.USE_BLE -> {
                Timber.i("PairingModule: Using BlePodManager (real BLE + TWICommand + text RHP)")
                BlePodManager(cryptoManager)
            }
            BuildConfig.USE_EMULATOR -> {
                Timber.i("PairingModule: Using EmulatorPodManager (TCP + real crypto)")
                EmulatorPodManager(cryptoManager)
            }
            else -> {
                Timber.i("PairingModule: Using FakePodManager")
                FakePodManager()
            }
        }
    }
}
