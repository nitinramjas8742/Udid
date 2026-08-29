package com.example.udid.data

/**
 * Repository for the MPI (Mental Peace Index) distracting-app configuration.
 *
 * Stores which apps the user considers distracting and what daily usage
 * limit they are comfortable with. The MPI calculation (a later step) will
 * read from [getEnabled] to compare actual usage against these limits.
 *
 * Only enabled apps are stored — toggling an app off simply deletes its row.
 */
class DistractingAppRepository(
    private val dao: DistractingAppConfigDao
) {

    /** Enable (or update the limit of) a distracting app. */
    suspend fun setDistracting(config: DistractingAppConfig) {
        dao.upsert(config)
    }

    /** Disable a distracting app (toggle off). */
    suspend fun removeDistracting(packageName: String) {
        dao.delete(packageName)
    }

    /** All currently-enabled distracting apps with their limits. */
    suspend fun getEnabled(): List<DistractingAppConfig> {
        return dao.getAll()
    }

    /** Just the package names of enabled distracting apps. */
    suspend fun getEnabledPackageNames(): List<String> {
        return dao.getEnabledPackageNames()
    }
}
