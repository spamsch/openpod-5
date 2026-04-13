package com.openpod.core.ble

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module providing BLE components.
 *
 * - [PodBleScanner] is a singleton — one scanner instance shared app-wide.
 * - [PodBleConnection] is **not** provided here because each connection
 *   requires a [CoroutineScope] tied to the caller's lifecycle. Use
 *   [PodBleConnectionFactory] to create connection instances on demand.
 * - [EnvelopeFramer] is not a singleton — each connection should use its own
 *   instance to avoid cross-contamination of reassembly state.
 */
@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    /**
     * Provides a singleton [PodBleScanner] backed by Kable.
     *
     * A single scanner is sufficient because Android allows only one
     * BLE scan at a time; concurrent scan requests are merged by the OS.
     */
    @Provides
    @Singleton
    fun providePodBleScanner(): PodBleScanner = KablePodScanner()

    /**
     * Provides a factory for creating [PodBleConnection] instances.
     *
     * Each connection attempt should get a fresh instance via this factory.
     * The caller supplies the [CoroutineScope] that controls the connection
     * lifecycle.
     */
    @Provides
    @Singleton
    fun providePodBleConnectionFactory(): PodBleConnectionFactory = PodBleConnectionFactory()
}

/**
 * Factory for creating [PodBleConnection] instances.
 *
 * Each connection to a pod requires its own [KablePodConnection] with
 * a dedicated coroutine scope. This factory encapsulates that creation.
 */
class PodBleConnectionFactory {

    /**
     * Create a new [PodBleConnection] for a single connection lifecycle.
     *
     * @param scope Coroutine scope that governs the connection's lifetime.
     *              Cancel this scope to tear down the connection.
     * @return A fresh connection instance ready for [PodBleConnection.connect].
     */
    fun create(scope: kotlinx.coroutines.CoroutineScope): PodBleConnection =
        KablePodConnection(scope)
}
