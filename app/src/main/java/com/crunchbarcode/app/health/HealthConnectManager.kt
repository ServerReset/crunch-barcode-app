package com.crunchbarcode.app.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

data class HealthData(
    val stepsToday: Int = 0,
    val stepsWeek: Int = 0,
    val workoutsThisWeek: Int = 0,
    val caloriesToday: Double = 0.0,
    val lastWorkoutName: String? = null,
    val lastWorkoutDate: String? = null,
    val isAvailable: Boolean = false,
    val isAuthorized: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val hasData: Boolean get() = isAvailable && isAuthorized
}

class HealthConnectManager(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        try { HealthConnectClient.getOrCreate(context) } catch (_: Exception) { null }
    }

    val isAvailable: Boolean get() = client != null

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    )

    fun getPermissionIntent(): Intent? = client?.let {
        PermissionController.createRequestPermissionResultContract().createIntent(it, permissions)
    }

    suspend fun hasPermissions(): Boolean = try {
        client?.permissionController?.getGrantedPermissions()?.containsAll(permissions) ?: false
    } catch (_: Exception) { false }

    suspend fun loadHealthData(): HealthData {
        val c = client ?: return HealthData(isAvailable = false)
        if (!hasPermissions()) return HealthData(isAvailable = true, isAuthorized = false)

        return try {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val dayStart = today.atStartOfDay(zone).toInstant()
            val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant()
            val dayRange = TimeRangeFilter.between(dayStart, dayEnd)
            val weekRange = TimeRangeFilter.between(weekStart, dayEnd)

            val stepsToday = try {
                c.readRecords(ReadRecordsRequest(StepsRecord::class, dayRange)).records
                    .sumOf { it.count }
            } catch (_: Exception) { 0L }.toInt()

            val stepsWeek = try {
                c.readRecords(ReadRecordsRequest(StepsRecord::class, weekRange)).records
                    .sumOf { it.count }
            } catch (_: Exception) { 0L }.toInt()

            val caloriesToday = try {
                c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, dayRange)).records
                    .sumOf { it.energy.inCalories }.roundToInt().toDouble()
            } catch (_: Exception) { 0.0 }

            val workouts = try {
                c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, weekRange)).records
            } catch (_: Exception) { emptyList<ExerciseSessionRecord>() }

            val workoutCount = workouts.size
            val lastWorkout = workouts.maxByOrNull { it.startTime }
            val lastWorkoutName = lastWorkout?.exerciseType?.let { type ->
                ExerciseSessionRecord::class.java.declaredFields
                    .filter { it.name.startsWith("EXERCISE_TYPE_") }
                    .firstOrNull { it.getInt(null) == type }
                    ?.name?.removePrefix("EXERCISE_TYPE_")
                    ?.lowercase()?.replace("_", " ")
                    ?.replaceFirstChar { c -> c.uppercase() }
            }
            val lastWorkoutDate = lastWorkout?.startTime?.atZone(zone)?.toLocalDate()?.toString()

            HealthData(stepsToday, stepsWeek, workoutCount, caloriesToday,
                lastWorkoutName, lastWorkoutDate, true, true)
        } catch (e: Exception) {
            HealthData(isAvailable = true, isAuthorized = true, error = e.localizedMessage)
        }
    }
}
