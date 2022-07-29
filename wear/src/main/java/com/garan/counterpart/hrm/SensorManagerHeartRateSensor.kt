package com.garan.counterpart.hrm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.garan.counterpart.TAG

class SensorManagerHeartRateSensor(private val context: Context) : HeartRateSensor() {
    private val SAMPLING_PERIOD_US = 1_000_000
    private val MAX_REPORTING_LATENCY_US = 30_000_000

    private val sensorManager by lazy { context.getSystemService(LifecycleService.SENSOR_SERVICE) as SensorManager }
    private val sensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    private var listener: SensorEventListener? = null

    override fun start() {
        listener = object : SensorEventListener {
            // Heart rate on Android should be on change.
            override fun onSensorChanged(event: SensorEvent) {
                latestValue = event.values.last().toInt()
                Log.i(
                    TAG,
                    "New value received from HR sensor: $latestValue (of ${event.values.size})"
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.i(TAG, "Accuracy changed: $accuracy")
            }
        }

        listener?.let {
            Log.i(TAG, "Enabling HR sensor")
            // Set the max latency to >> higher than the alarm, so that the alarm drives the
            // reporting frequency
            sensorManager.registerListener(listener, sensor, SAMPLING_PERIOD_US, MAX_REPORTING_LATENCY_US)
        }
    }

    override fun stop() {
        listener?.let {
            sensorManager.unregisterListener(listener)
        }
        listener = null
    }

    override fun flush() { sensorManager.flush(listener) }
}