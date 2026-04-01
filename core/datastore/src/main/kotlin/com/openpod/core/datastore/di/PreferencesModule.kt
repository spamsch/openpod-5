package com.openpod.core.datastore.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.openpod.core.datastore.OpenPodPreferences
import com.openpod.core.datastore.OpenPodPreferencesImpl
import com.openpod.core.datastore.PinManager
import com.openpod.core.datastore.PinManagerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier annotation to distinguish the DataStore AEAD from any other
 * AEAD instances in the dependency graph (e.g., the database module's AEAD).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DataStoreAead

/**
 * Hilt module providing encrypted DataStore preferences and PIN management.
 *
 * The DataStore file is encrypted using Tink's AEAD primitive backed by
 * Android Keystore. This ensures all user preferences (onboarding state,
 * glucose unit, etc.) are encrypted at rest.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PreferencesProviderModule {

    /** DataStore file name (without extension — DataStore appends .preferences_pb). */
    private const val DATASTORE_FILE_NAME = "openpod_preferences"

    /** Tink keyset preference file for the DataStore encryption key. */
    private const val KEYSET_PREF_FILE = "openpod_datastore_keyset_prefs"

    /** Tink keyset name. */
    private const val KEYSET_NAME = "openpod_datastore_keyset"

    /** Android Keystore alias for the DataStore master key. */
    private const val MASTER_KEY_ALIAS = "openpod_datastore_master_key"

    /** SharedPreferences file for PIN storage. */
    private const val PIN_PREFS_FILE = "openpod_pin_prefs"

    /**
     * Provides the Tink [Aead] primitive for DataStore and PIN encryption.
     */
    @Provides
    @Singleton
    @DataStoreAead
    fun provideDataStoreAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        val keysetHandle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
        Timber.d("Tink AEAD keyset loaded for DataStore encryption")
        return keysetHandle.getPrimitive(Aead::class.java)
    }

    /**
     * Provides the DataStore instance for user preferences.
     *
     * Note: DataStore Preferences does not natively support Tink encryption
     * of the file itself. The data stored here (onboarding flags, glucose unit)
     * is non-sensitive settings data. Sensitive data (PIN hash) uses the
     * separate AEAD-encrypted SharedPreferences path.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        Timber.d("Creating DataStore for preferences")
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(DATASTORE_FILE_NAME)
        }
    }

    /**
     * Provides the encrypted SharedPreferences for PIN storage.
     */
    @Provides
    @Singleton
    fun providePinSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(PIN_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Provides the [PinManager] implementation wired with the DataStore AEAD.
     */
    @Provides
    @Singleton
    fun providePinManager(
        @DataStoreAead aead: Aead,
        sharedPreferences: SharedPreferences,
    ): PinManager {
        return PinManagerImpl(aead, sharedPreferences)
    }
}

/**
 * Hilt binding module for the [OpenPodPreferences] interface.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PreferencesBindingModule {

    /**
     * Binds the [OpenPodPreferencesImpl] to the [OpenPodPreferences] interface.
     */
    @Binds
    @Singleton
    abstract fun bindOpenPodPreferences(impl: OpenPodPreferencesImpl): OpenPodPreferences
}
