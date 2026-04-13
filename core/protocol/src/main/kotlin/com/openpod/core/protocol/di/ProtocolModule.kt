package com.openpod.core.protocol.di

import com.openpod.core.protocol.activation.ActivationStateMachine
import com.openpod.core.protocol.activation.AidSetupStateMachine
import com.openpod.core.protocol.framing.EnvelopeFramer
import com.openpod.core.protocol.rhp.RhpCommandBuilder
import com.openpod.core.protocol.rhp.RhpCommandParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for the protocol layer.
 *
 * Provides protocol components that are shared across the application.
 * Session-scoped objects (like [PodSession]) are created by the session
 * manager when a connection is established, not provided by this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProtocolModule {

    /**
     * Provides the RHP command serializer.
     *
     * Singleton because it holds no mutable state.
     */
    @Provides
    @Singleton
    fun provideRhpCommandBuilder(): RhpCommandBuilder = RhpCommandBuilder()

    /**
     * Provides the RHP response parser.
     *
     * Singleton because it holds no mutable state.
     */
    @Provides
    @Singleton
    fun provideRhpCommandParser(): RhpCommandParser = RhpCommandParser()

    /**
     * Provides the envelope framer for message chunking and reassembly.
     *
     * Note: Each [PodSession] creates its own framer to maintain independent
     * reassembly buffers. This module-level framer is available for
     * standalone use (e.g., in tests or tools).
     */
    @Provides
    @Singleton
    fun provideEnvelopeFramer(): EnvelopeFramer = EnvelopeFramer()

    /**
     * Provides a fresh activation state machine.
     *
     * Not singleton — a new instance is needed for each pod activation attempt.
     */
    @Provides
    fun provideActivationStateMachine(): ActivationStateMachine = ActivationStateMachine()

    /**
     * Provides a fresh AID setup state machine.
     *
     * Not singleton — a new instance is needed for each AID configuration flow.
     */
    @Provides
    fun provideAidSetupStateMachine(): AidSetupStateMachine = AidSetupStateMachine()
}
