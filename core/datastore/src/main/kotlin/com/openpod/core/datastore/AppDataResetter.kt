package com.openpod.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clears all persisted app data so the app returns to the first-launch state.
 *
 * Wipes:
 * - DataStore preferences (onboarding state, glucose unit, etc.)
 * - PIN storage (encrypted hash)
 * - Room database (insulin profiles, pod sessions, basal programs)
 * - Tink encryption keysets
 * - Database passphrase
 *
 * After calling [resetAllData], the process should be killed so that
 * Hilt singletons (database, DataStore) reinitialise cleanly on restart.
 */
@Singleton
class AppDataResetter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val pinManager: PinManager,
) {

    /**
     * Wipe all persisted data and return the app to first-launch state.
     *
     * The caller is responsible for killing the process after this returns,
     * since singleton instances (Room DB, DataStore) hold stale references.
     */
    suspend fun resetAllData() {
        Timber.w("AppDataResetter: Resetting ALL app data")

        // 1. Clear DataStore preferences
        dataStore.edit { it.clear() }
        Timber.i("AppDataResetter: DataStore preferences cleared")

        // 2. Clear PIN
        pinManager.clearPin()
        Timber.i("AppDataResetter: PIN cleared")

        // 3. Clear SharedPreferences files
        SHARED_PREFS_FILES.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            Timber.i("AppDataResetter: SharedPreferences '%s' cleared", name)
        }

        // 4. Delete Room database
        val deleted = context.deleteDatabase(DATABASE_NAME)
        Timber.i("AppDataResetter: Database '%s' deleted=%b", DATABASE_NAME, deleted)

        Timber.w("AppDataResetter: Reset complete — process should be killed now")
    }

    private companion object {
        const val DATABASE_NAME = "openpod.db"

        val SHARED_PREFS_FILES = listOf(
            "openpod_pin_prefs",
            "openpod_db_passphrase_prefs",
            "openpod_datastore_keyset_prefs",
        )
    }
}
