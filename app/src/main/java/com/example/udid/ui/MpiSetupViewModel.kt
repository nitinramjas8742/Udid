package com.example.udid.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.udid.data.DistractingAppConfig
import com.example.udid.data.DistractingAppRepository
import com.example.udid.usage.UsageEventReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One entry in the app list shown on the MPI setup screen.
 * Carries the device-display info plus whether the user has toggled it on.
 */
data class AppSetupEntry(
    val packageName: String,
    val appName: String,
    val isDistracting: Boolean,
    val dailyLimitMinutes: Int
)

/** UI state for the MPI setup screen. */
data class MpiSetupUiState(
    val apps: List<AppSetupEntry> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * ViewModel for the MPI setup screen.
 *
 * Loads all launchable apps on the device, merges them with the user's
 * existing [DistractingAppConfig] settings from Room, and exposes a list
 * of [AppSetupEntry] items the UI can render. All Room and PackageManager
 * work runs on [Dispatchers.IO].
 */
class MpiSetupViewModel(
    private val repository: DistractingAppRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MpiSetupUiState())
    val uiState: StateFlow<MpiSetupUiState> = _uiState.asStateFlow()

    init {
        loadApps()
    }

    /** Reload from device + Room (e.g. after re-entering the screen). */
    fun refresh() {
        loadApps()
    }

    /** Toggle an app on or off. */
    fun toggleApp(packageName: String) {
        val current = _uiState.value.apps
        val entry = current.find { it.packageName == packageName } ?: return

        viewModelScope.launch(Dispatchers.IO) {
            if (entry.isDistracting) {
                repository.removeDistracting(packageName)
            } else {
                repository.setDistracting(
                    DistractingAppConfig(
                        packageName = packageName,
                        appName = entry.appName,
                        dailyLimitMinutes = entry.dailyLimitMinutes
                    )
                )
            }
            refresh()
        }
    }

    /** Update the daily limit for an app (only meaningful if it is enabled). */
    fun updateLimit(packageName: String, minutes: Int) {
        val current = _uiState.value.apps
        val entry = current.find { it.packageName == packageName } ?: return
        if (!entry.isDistracting) return

        viewModelScope.launch(Dispatchers.IO) {
            repository.setDistracting(
                DistractingAppConfig(
                    packageName = packageName,
                    appName = entry.appName,
                    dailyLimitMinutes = minutes
                )
            )
            refresh()
        }
    }

    private fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val launchable = UsageEventReader(context).getFilteredLaunchableApps()
            val enabledConfigs = repository.getEnabled().associateBy { it.packageName }

            val entries = launchable.map { (pkg, name) ->
                val config = enabledConfigs[pkg]
                AppSetupEntry(
                    packageName = pkg,
                    appName = name,
                    isDistracting = config != null,
                    dailyLimitMinutes = config?.dailyLimitMinutes ?: DEFAULT_LIMIT_MINUTES
                )
            }.sortedWith(
                compareByDescending<AppSetupEntry> { it.isDistracting }
                    .thenBy { it.appName.lowercase() }
            )

            _uiState.value = MpiSetupUiState(apps = entries, isLoading = false)
        }
    }

    companion object {
        /** Default limit when a new app is first toggled on (minutes). */
        const val DEFAULT_LIMIT_MINUTES = 45

        fun factory(repository: DistractingAppRepository, context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MpiSetupViewModel(repository, context) as T
                }
            }
        }
    }
}
