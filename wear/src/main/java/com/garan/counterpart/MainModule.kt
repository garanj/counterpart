package com.garan.counterpart

import android.content.Context
import android.util.Log
import com.garan.counterpart.hrm.ExerciseClientHeartRateSensor
import com.garan.counterpart.hrm.HeartRateSensor
import com.garan.counterpart.hrm.SensorManagerHeartRateSensor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Counterpart can use either Health Services for measuring heart rate or SensorManager. Specify
 * which one should be injected.
 */
const val useHealthServices = true

@Module
@InstallIn(SingletonComponent::class)
class ProviderModule {
    @Singleton
    @Provides
    fun provideApplicationCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Singleton
    @Provides
    fun providesHeartRateSensor(
        @ApplicationContext appContext: Context,
        coroutineScopeProvider: Provider<CoroutineScope>
    ): HeartRateSensor = if (useHealthServices) {
        Log.i(TAG, "Using health services")
        ExerciseClientHeartRateSensor(appContext, coroutineScopeProvider.get())
    } else {
        Log.i(TAG, "Using sensor manager")
        SensorManagerHeartRateSensor(appContext)
    }
}