package com.openpod.feature.pairing.di

import com.openpod.core.crypto.CryptoManager
import com.openpod.feature.pairing.BuildConfig
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
 * Singleton-scoped so the TCP connection survives navigation from
 * pairing to dashboard. Switches between:
 * - [EmulatorPodManager]: Connects to the Python pod emulator over TCP
 *   with real crypto. Enabled via BuildConfig.USE_EMULATOR.
 * - [FakePodManager]: Returns simulated data for UI development.
 *
 * Build with emulator mode:
 * ```
 * ./gradlew :app:installDebug -PuseEmulator=true
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object PairingModule {

    @Provides
    @Singleton
    fun providePodManager(
        cryptoManager: CryptoManager,
    ): PodManager {
        return if (BuildConfig.USE_EMULATOR) {
            Timber.i("PairingModule: Using EmulatorPodManager (TCP + real crypto)")
            EmulatorPodManager(cryptoManager)
        } else {
            Timber.i("PairingModule: Using FakePodManager")
            FakePodManager()
        }
    }
}
