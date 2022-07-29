package com.garan.counterpart.hrm

/**
 * Represents a heart rate sensor provider, to allow generalization across [SensorManager] and
 * [ExerciseClient].
 */
abstract class HeartRateSensor {
    var latestValue = 0

    abstract fun start()
    abstract fun stop()
    abstract fun flush()
}

