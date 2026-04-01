package com.openpod.core.crypto.di

import com.openpod.core.crypto.CryptoManager
import com.openpod.core.crypto.PureKotlinCryptoManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the [CryptoManager] singleton.
 *
 * Binds [PureKotlinCryptoManager] which uses portable Kotlin/Java crypto
 * (Bouncy Castle + JCA). Works on all Android ABIs without native libraries.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CryptoModule {

    @Binds
    @Singleton
    abstract fun bindCryptoManager(impl: PureKotlinCryptoManager): CryptoManager
}
