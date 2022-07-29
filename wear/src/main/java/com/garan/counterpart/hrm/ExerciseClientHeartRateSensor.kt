package com.garan.counterpart.hrm

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseUpdateListener
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.*
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
    private val client by lazy { HealthServices.getClient(context).exerciseClient }

    override fun start() {
        val exerciseUpdateListener = object : ExerciseUpdateListener {
            override fun onExerciseUpdate(update: ExerciseUpdate) {
                update.latestMetrics[DataType.HEART_RATE_BPM]?.let { dataPoints ->
                    latestValue = dataPoints.last().value.asDouble().toInt()
                    Log.i(TAG, "New value received from HR sensor: $latestValue (of ${dataPoints.size})")
                }
            }

            override fun onAvailabilityChanged(dataType: DataType, availability: Availability) {
                if (dataType == DataType.HEART_RATE_BPM) {
                    val dataTypeAvailability = availability as DataTypeAvailability
                    Log.i(TAG, "HR Availability: ${dataTypeAvailability}")
                    if (availability != DataTypeAvailability.AVAILABLE) {
                        latestValue = 0
                    }
                }
            }
            override fun onLapSummary(lapSummary: ExerciseLapSummary) {}
        }

        coroutineScope.launch {
            client.setUpdateListener(exerciseUpdateListener).await()
            val config = ExerciseConfig.builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                // Should set this value correctly where possible to assist with calorie
                // calculations.
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

    override fun flush() {
        runBlocking {
            client.flushExercise().await()
        }
    }
}