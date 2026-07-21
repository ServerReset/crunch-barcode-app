package com.crunchbarcode.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
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
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Exception) { null }
    }

    val isAvailable: Boolean get() = client != null

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    )

    fun getPermissionIntent() = client?.let {
        PermissionController.createRequestPermissionResultContract()
            .createIntent(it, permissions)
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
                val stepsResp = c.aggregate(
                    AggregateRequest(
                        StepsRecord::class,
                        todayRange,
                        emptySet()
                    )
                )
                (stepsResp[StepsRecord.COUNT_TOTAL]?.inWholeSteps ?: 0).toInt()
            } catch (_: Exception) { 0 }

            val stepsWeek = try {
                val stepsWeekResp = c.aggregate(
                    AggregateRequest(
                        StepsRecord::class,
                        weekRange,
                        emptySet()
                    )
                )
                (stepsWeekResp[StepsRecord.COUNT_TOTAL]?.inWholeSteps ?: 0).toInt()
            } catch (_: Exception) { 0 }

            val caloriesToday = try {
                val calResp = c.aggregate(
                    AggregateRequest(
                        TotalCaloriesBurnedRecord::class,
                        todayRange,
                        emptySet()
                    )
                )
                calResp[TotalCaloriesBurnedRecord.CALORIES_TOTAL]?.inCalories ?: 0.0
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
                val names = ExerciseSessionRecord::class.java.fields
                    .filter { it.name.startsWith("EXERCISE_TYPE_") && it.getInt(null) == type }
                    .map { it.name.removePrefix("EXERCISE_TYPE_")
                        .lowercase().replace("_", " ")
                        .replaceFirstChar { c -> c.uppercase() }
                    }
                names.firstOrNull()
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
                isAuthorized = true,
                isLoading = false
            )
        } catch (e: Exception) {
            HealthData(isAvailable = true, isAuthorized = true, isLoading = false, error = e.localizedMessage)
        }
    }
}
