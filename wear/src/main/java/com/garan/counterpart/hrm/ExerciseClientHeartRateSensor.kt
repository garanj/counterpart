package com.garan.counterpart.hrm

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseUpdateListener
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
import androidx.lifecycle.LifecycleService
import com.garan.counterpart.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

class ExerciseClientHeartRateSensor @Inject constructor(
    @ApplicationContext val context: Context,
    private val coroutineScope: CoroutineScope
) : HeartRateSensor() {
    private val MILESTONE_INTERVAL_SECONDS = 10L
    private val client by lazy { HealthServices.getClient(context).exerciseClient }

    override fun start(onSendBlock: (Int) -> Unit) {
        sendBlock = onSendBlock
        val exerciseUpdateListener = object : ExerciseUpdateListener {
            override fun onExerciseUpdate(update: ExerciseUpdate) {
                update.latestMetrics[DataType.HEART_RATE_BPM]?.let { dataPoints ->
                    val latestValue = dataPoints.last().value.asDouble().toInt()
                    Log.i(TAG, "New value received from HR sensor: $latestValue (of ${dataPoints.size})")
                    sendBlock.invoke(latestValue)
                }
            }

            override fun onAvailabilityChanged(dataType: DataType, availability: Availability) {
                if (dataType == DataType.HEART_RATE_BPM) {
                    val dataTypeAvailability = availability as DataTypeAvailability
                    Log.i(TAG, "HR Availability: ${dataTypeAvailability}")
                }
            }
            override fun onLapSummary(lapSummary: ExerciseLapSummary) {}
        }

        coroutineScope.launch {
            client.setUpdateListener(exerciseUpdateListener).await()
            // For the Health Services wakeup, this experiment uses a repeated milestone, instead of
            // the alarm-based approach of Sensor manager. This should really be used in conjunction
            // with Capabilities to check whether this is supported on the device, as theoretically
            // some devices may or may not support this, and the sample interval support may be
            // different too.
            val milestone = ExerciseGoal.createMilestone(
                condition = DataTypeCondition(
                    DataType.ACTIVE_EXERCISE_DURATION,
                    Value.ofLong(MILESTONE_INTERVAL_SECONDS),
                    ComparisonType.GREATER_THAN_OR_EQUAL
                ),
                Value.ofLong(MILESTONE_INTERVAL_SECONDS)
            )

            val config = ExerciseConfig.builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                // Should set this value correctly where possible to assist with calorie
                // calculations.
                .setExerciseGoals(listOf(milestone))
                .setExerciseType(ExerciseType.WORKOUT)
                .setShouldEnableAutoPauseAndResume(false)
                .setShouldEnableGps(false)
                .build()
            client.startExercise(config).await()
        }
    }

    override fun stop() {
        coroutineScope.launch {
            client.endExercise().await()
        }
    }
}