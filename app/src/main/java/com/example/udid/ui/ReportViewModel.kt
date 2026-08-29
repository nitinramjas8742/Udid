package com.example.udid.ui

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.udid.data.ReportRepository
import com.example.udid.data.UsageReport
import com.example.udid.util.ReportImageExporter
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which report period is currently being viewed. */
enum class ReportPeriod { DAILY, WEEKLY, MONTHLY }

/**
 * Immutable UI state for the reports screen.
 *
 * `report` is null while the first load is in progress or before any period
 * has produced data; `isLoading` is true while a background query is running;
 * `isSharing` is true while the shareable card is being generated.
 */
data class ReportUiState(
    val report: UsageReport? = null,
    val isLoading: Boolean = false,
    val isSharing: Boolean = false
)

/**
 * State holder for the Daily/Weekly/Monthly reports.
 *
 * Holds the currently selected period and the reference millis (the week /
 * month / day currently on screen) and fetches a [UsageReport] from the
 * repository whenever either changes. All database reads run on
 * [Dispatchers.IO] so the main thread is never blocked.
 */
class ReportViewModel(
    private val repository: ReportRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState(isLoading = true))
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    private val _period = MutableStateFlow(ReportPeriod.DAILY)
    val period: StateFlow<ReportPeriod> = _period.asStateFlow()

    /** Epoch millis: a day's date, the start of the current week/month, etc. */
    private val _referenceMillis = MutableStateFlow(System.currentTimeMillis())
    val referenceMillis: StateFlow<Long> = _referenceMillis.asStateFlow()

    init {
        loadReport()
    }

    /** Switch the report's granularity (Daily/Weekly/Monthly). */
    fun setPeriod(newPeriod: ReportPeriod) {
        if (_period.value == newPeriod) return
        _period.value = newPeriod
        // Reset the reference to "now" so switching always opens current period.
        _referenceMillis.value = System.currentTimeMillis()
        loadReport()
    }

    /** Move forwards (future) or backwards (past) in the current period. */
    fun shiftPeriod(delta: Int) {
        val cal = Calendar.getInstance()
            .apply { timeInMillis = _referenceMillis.value }

        when (_period.value) {
            ReportPeriod.DAILY -> cal.add(Calendar.DAY_OF_MONTH, delta)
            ReportPeriod.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, delta)
            ReportPeriod.MONTHLY -> cal.add(Calendar.MONTH, delta)
        }

        _referenceMillis.value = cal.timeInMillis
        loadReport()
    }

    /** Re-query the current period (call after new usage data is persisted). */
    fun refresh() {
        loadReport()
    }

    /**
     * Generate and share a PNG of the currently viewed report. Shows a loading
     * indicator on the Share button while working and ignores re-taps until it
     * finishes. Compose capture must run on the main thread, so the actual
     * generation happens here on the main dispatcher (the data is already in
     * memory — no database read is needed).
     */
    fun shareCurrentReport(context: Context) {
        val activity = context as? Activity ?: return
        val currentReport = _uiState.value.report ?: return
        if (_uiState.value.isSharing) return

        _uiState.value = _uiState.value.copy(isSharing = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ReportImageExporter.share(activity, _period.value, currentReport)
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isSharing = false)
                }
            }
        }
    }

    /** Label like "Aug 2026" / "Aug 24 - 30" / "Aug 28, 2026". */
    fun periodLabel(): String {
        val cal = Calendar.getInstance().apply { timeInMillis = _referenceMillis.value }
        return when (_period.value) {
            ReportPeriod.WEEKLY -> {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = _referenceMillis.value
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                }
                val start = startCal.timeInMillis
                val endCal = Calendar.getInstance().apply { timeInMillis = start }
                endCal.add(Calendar.DAY_OF_MONTH, 6)
                monthDay(start) + " - " + monthDay(endCal.timeInMillis)
            }
            ReportPeriod.MONTHLY -> {
                val fmt = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                fmt.format(java.util.Date(cal.timeInMillis))
            }
            ReportPeriod.DAILY -> {
                val fmt = java.text.SimpleDateFormat("EEE, MMM d, yyyy", java.util.Locale.getDefault())
                fmt.format(java.util.Date(cal.timeInMillis))
            }
        }
    }

    private fun loadReport() {
        _uiState.value = _uiState.value.copy(isLoading = true)

        val currentPeriod = _period.value
        val currentReference = _referenceMillis.value

        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                when (currentPeriod) {
                    ReportPeriod.DAILY -> repository.getDailyReport(currentReference)
                    ReportPeriod.WEEKLY -> repository.getWeeklyReport(currentReference)
                    ReportPeriod.MONTHLY -> repository.getMonthlyReport(currentReference)
                }
            }

            // Ignore stale results if the user navigated while loading.
            if (_period.value == currentPeriod &&
                _referenceMillis.value == currentReference
            ) {
                _uiState.value = ReportUiState(report = report, isLoading = false)
            }
        }
    }

    private fun monthDay(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }

    companion object {
        fun factory(repository: ReportRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReportViewModel(repository) as T
                }
            }
        }
    }
}
