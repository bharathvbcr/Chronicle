package com.chronicle.app.vm

import com.chronicle.app.health.HealthConnectAvailability
import com.chronicle.app.health.HealthDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Health Connect import / sync UI state. */
class HealthStateHolder {
    private val _healthByDate = MutableStateFlow<Map<String, HealthDay>>(emptyMap())
    val healthByDate: StateFlow<Map<String, HealthDay>> = _healthByDate.asStateFlow()

    private val _healthAutoSync = MutableStateFlow(false)
    val healthAutoSync: StateFlow<Boolean> = _healthAutoSync.asStateFlow()

    private val _healthLastImportMs = MutableStateFlow<Long?>(null)
    val healthLastImportMs: StateFlow<Long?> = _healthLastImportMs.asStateFlow()

    private val _healthImporting = MutableStateFlow(false)
    val healthImporting: StateFlow<Boolean> = _healthImporting.asStateFlow()

    private val _healthPermissionsGranted = MutableStateFlow(false)
    val healthPermissionsGranted: StateFlow<Boolean> = _healthPermissionsGranted.asStateFlow()

    private val _healthAvailability =
        MutableStateFlow(HealthConnectAvailability.UNAVAILABLE)
    val healthAvailability: StateFlow<HealthConnectAvailability> =
        _healthAvailability.asStateFlow()

    internal val healthByDateMutable get() = _healthByDate
    internal val healthAutoSyncMutable get() = _healthAutoSync
    internal val healthLastImportMsMutable get() = _healthLastImportMs
    internal val healthImportingMutable get() = _healthImporting
    internal val healthPermissionsGrantedMutable get() = _healthPermissionsGranted
    internal val healthAvailabilityMutable get() = _healthAvailability

    fun clear() {
        _healthByDate.value = emptyMap()
        _healthImporting.value = false
    }
}
