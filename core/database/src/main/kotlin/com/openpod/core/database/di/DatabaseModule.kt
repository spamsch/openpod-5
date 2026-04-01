package com.openpod.core.database.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.openpod.core.database.OpenPodDatabase
import com.openpod.core.database.dao.BasalProgramDao
import com.openpod.core.database.dao.HistoryEventDao
import com.openpod.core.database.dao.InsulinProfileDao
import com.openpod.core.database.dao.PodSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the database-specific Tink AEAD to avoid Hilt conflicts
 * with the DataStore module's AEAD.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DatabaseAead

/**
 * Hilt module providing the encrypted Room database and its DAOs.
 *
 * The database is encrypted with SQLCipher. The encryption passphrase is a
 * randomly generated 32-byte key, created once on first launch and stored
 * encrypted (via Tink AEAD backed by Android Keystore). On subsequent launches
 * the stored ciphertext is decrypted to recover the same passphrase.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    /** Tink keyset preference file name. */
    private const val KEYSET_PREF_FILE = "openpod_db_keyset_prefs"

    /** Tink keyset name within the preference file. */
    private const val KEYSET_NAME = "openpod_db_keyset"

    /** Android Keystore alias for the master key protecting the Tink keyset. */
    private const val MASTER_KEY_ALIAS = "openpod_db_master_key"

    /** SharedPreferences file for storing the encrypted DB passphrase. */
    private const val PASSPHRASE_PREF_FILE = "openpod_db_passphrase_prefs"

    /** SharedPreferences key for the Base64-encoded encrypted passphrase. */
    private const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_passphrase"

    /** Associated data for the AEAD encryption — binds the ciphertext to DB passphrase usage. */
    private val PASSPHRASE_AAD = "db-passphrase".toByteArray()

    /** Length of the randomly generated passphrase in bytes. */
    private const val PASSPHRASE_LENGTH_BYTES = 32

    /**
     * Provides the Tink [Aead] primitive for database passphrase encryption,
     * backed by Android Keystore.
     *
     * The keyset is created once and persisted in encrypted SharedPreferences.
     * All subsequent reads decrypt the keyset using the hardware-backed master key.
     */
    @Provides
    @Singleton
    @DatabaseAead
    fun provideAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        val keysetHandle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
        Timber.d("Tink AEAD keyset loaded for database encryption")
        return keysetHandle.getPrimitive(Aead::class.java)
    }

    /**
     * Provides the encrypted [OpenPodDatabase] singleton.
     *
     * On first launch, a random 32-byte passphrase is generated, encrypted
     * with the Tink AEAD, and stored as Base64 in SharedPreferences. On
     * subsequent launches the stored ciphertext is decrypted to recover the
     * same passphrase. This guarantees SQLCipher always receives the same key.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        @DatabaseAead aead: Aead,
    ): OpenPodDatabase {
        // SQLCipher native library must be loaded before any database operations.
        System.loadLibrary("sqlcipher")

        val prefs = context.getSharedPreferences(PASSPHRASE_PREF_FILE, Context.MODE_PRIVATE)
        val passphrase = getOrCreatePassphrase(aead, prefs)
        val factory = SupportOpenHelperFactory(passphrase)

        Timber.i("Opening encrypted OpenPod database")

        return Room.databaseBuilder(
            context,
            OpenPodDatabase::class.java,
            OpenPodDatabase.DATABASE_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(OpenPodDatabase.MIGRATION_1_2)
            .build()
    }

    /**
     * Retrieve the existing passphrase or generate and store a new one.
     *
     * The passphrase is a random 32-byte array, encrypted with the AEAD and
     * stored as a Base64 string. On retrieval, the ciphertext is decrypted to
     * recover the original bytes.
     *
     * @param aead The Tink AEAD primitive for encrypt/decrypt.
     * @param prefs SharedPreferences for persisting the encrypted passphrase.
     * @return The 32-byte SQLCipher passphrase.
     */
    private fun getOrCreatePassphrase(aead: Aead, prefs: SharedPreferences): ByteArray {
        val existing = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        if (existing != null) {
            return try {
                val ciphertext = Base64.getDecoder().decode(existing)
                aead.decrypt(ciphertext, PASSPHRASE_AAD).also {
                    Timber.d("Existing database passphrase decrypted successfully")
                }
            } catch (e: GeneralSecurityException) {
                Timber.e(e, "Failed to decrypt stored database passphrase")
                throw IllegalStateException("Cannot decrypt database passphrase — data may be corrupted", e)
            }
        }

        // First launch: generate a new random passphrase.
        Timber.i("Generating new database passphrase (first launch)")
        val passphrase = ByteArray(PASSPHRASE_LENGTH_BYTES).also {
            SecureRandom().nextBytes(it)
        }

        val ciphertext = try {
            aead.encrypt(passphrase, PASSPHRASE_AAD)
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "Failed to encrypt new database passphrase")
            throw IllegalStateException("Cannot encrypt database passphrase", e)
        }

        val encoded = Base64.getEncoder().encodeToString(ciphertext)
        prefs.edit().putString(KEY_ENCRYPTED_PASSPHRASE, encoded).commit()
        Timber.i("Database passphrase encrypted and stored")

        return passphrase
    }

    /** Provides the [InsulinProfileDao] singleton. */
    @Provides
    @Singleton
    fun provideInsulinProfileDao(database: OpenPodDatabase): InsulinProfileDao =
        database.insulinProfileDao()

    /** Provides the [BasalProgramDao] singleton. */
    @Provides
    @Singleton
    fun provideBasalProgramDao(database: OpenPodDatabase): BasalProgramDao =
        database.basalProgramDao()

    /** Provides the [PodSessionDao] singleton. */
    @Provides
    @Singleton
    fun providePodSessionDao(database: OpenPodDatabase): PodSessionDao =
        database.podSessionDao()

    /** Provides the [HistoryEventDao] singleton. */
    @Provides
    @Singleton
    fun provideHistoryEventDao(database: OpenPodDatabase): HistoryEventDao =
        database.historyEventDao()
}
