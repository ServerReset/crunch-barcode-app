package com.crunchbarcode.app.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return try {
            c.permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (_: Exception) { false }
    }

    suspend fun loadHealthData(): HealthData {
        val c = client ?: return HealthData(isAvailable = false)
        if (!hasPermissions()) return HealthData(isAvailable = true, isAuthorized = false)

        return try {
            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneId.systemDefault()).toInstant()

            val todayRange = TimeRangeFilter.between(startOfDay, endOfDay)
            val weekRange = TimeRangeFilter.between(startOfWeek, endOfDay)

            val stepsToday = try {
                val resp = c.aggregateGroup(
                    AggregateGroupRequest(
                        StepsRecord::class,
                        todayRange,
                        setOf(StepsRecord.COUNT_TOTAL)
                    )
                )
                resp[StepsRecord.COUNT_TOTAL]?.inSteps?.toInt() ?: 0
            } catch (_: Exception) { 0 }

            val stepsWeek = try {
                val resp = c.aggregateGroup(
                    AggregateGroupRequest(
                        StepsRecord::class,
                        weekRange,
                        setOf(StepsRecord.COUNT_TOTAL)
                    )
                )
                resp[StepsRecord.COUNT_TOTAL]?.inSteps?.toInt() ?: 0
            } catch (_: Exception) { 0 }

            val caloriesToday = try {
                val resp = c.aggregateGroup(
                    AggregateGroupRequest(
                        TotalCaloriesBurnedRecord::class,
                        todayRange,
                        setOf(TotalCaloriesBurnedRecord.CALORIES_TOTAL)
                    )
                )
                val energy = resp[TotalCaloriesBurnedRecord.CALORIES_TOTAL]
                energy?.inCalories ?: 0.0
            } catch (_: Exception) { 0.0 }

            val workoutsResp = try {
                c.readRecords(
                    ReadRecordsRequest(
                        ExerciseSessionRecord::class,
                        weekRange
                    )
                )
            } catch (_: Exception) { null }

            val workoutCount = workoutsResp?.records?.size ?: 0
            val lastWorkout = workoutsResp?.records?.maxByOrNull { it.startTime }
            val lastWorkoutName = lastWorkout?.exerciseType?.let { type ->
                ExerciseSessionRecord::class.java.declaredFields
                    .filter { it.name.startsWith("EXERCISE_TYPE_") }
                    .firstOrNull { it.getInt(null) == type }
                    ?.name?.removePrefix("EXERCISE_TYPE_")
                    ?.lowercase()?.replace("_", " ")
                    ?.replaceFirstChar { c -> c.uppercase() }
            }
            val lastWorkoutDate = lastWorkout?.startTime?.atZone(ZoneId.systemDefault())?.toLocalDate()?.toString()

            HealthData(
                stepsToday = stepsToday,
                stepsWeek = stepsWeek,
                workoutsThisWeek = workoutCount,
                caloriesToday = caloriesToday,
                lastWorkoutName = lastWorkoutName,
                lastWorkoutDate = lastWorkoutDate,
                isAvailable = true,
                isAuthorized = true
            )
        } catch (e: Exception) {
            HealthData(isAvailable = true, isAuthorized = true, error = e.localizedMessage)
        }
    }
}
