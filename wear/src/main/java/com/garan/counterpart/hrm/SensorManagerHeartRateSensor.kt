package com.garan.counterpart.hrm

import android.app.AlarmManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.garan.counterpart.TAG

class SensorManagerHeartRateSensor(private val context: Context) : HeartRateSensor() {
    private val SAMPLING_PERIOD_US = 1_000_000
    private val MAX_REPORTING_LATENCY_US = 30_000_000
    private val SEND_INTERVAL_MS = 5000L

    private val sensorManager by lazy { context.getSystemService(LifecycleService.SENSOR_SERVICE) as SensorManager }
    private val sensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    private var listener: SensorEventListener? = null

    private val handler = Handler(Looper.getMainLooper())

    // Use an alarm to wake up the app and flush the sensor, typically at the 5s interval
    private val alarmManager by lazy { context.getSystemService(LifecycleService.ALARM_SERVICE) as AlarmManager }
    private val alarmListener = object: AlarmManager.OnAlarmListener {
        override fun onAlarm() {
            Log.i(TAG, "RTC wakeup alarm")
            sendBlock.invoke(latestValue)

            setNextAlarm()
        }
    }

    override fun start(onSendBlock: (Int) -> Unit) {
        sendBlock = onSendBlock
        listener = object : SensorEventListener {
            // Heart rate on Android should be on change.
            override fun onSensorChanged(event: SensorEvent) {
                latestValue = event.values.last().toInt()
                Log.i(TAG, "New value received: $latestValue (of ${event.values.size})")
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
        setNextAlarm()
    }

    override fun stop() {
        cancelAlarm()
        listener?.let {
            sensorManager.unregisterListener(listener)
        }
        listener = null
    }

    private fun setNextAlarm() {
        val now = System.currentTimeMillis()
        alarmManager.setExact(
            AlarmManager.RTC_WAKEUP,
            now + SEND_INTERVAL_MS,
            "counterpart_alarm",
            alarmListener,
            handler
        )
    }

    private fun cancelAlarm() {
        alarmManager.cancel(alarmListener)
    }
}