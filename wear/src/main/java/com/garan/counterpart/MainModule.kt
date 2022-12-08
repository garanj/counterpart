package com.garan.counterpart

import android.content.Context
import com.garan.counterpart.hrm.HeartRateSensor
import com.garan.counterpart.hrm.SensorManagerHeartRateSensor
import com.garan.counterpart.network.DataLayerHrSenderClient
import com.garan.counterpart.network.GrpcHrSenderClient
import com.garan.counterpart.network.HrSenderClient
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


const val useGrpc = true

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
        @ApplicationContext appContext: Context
    ): HeartRateSensor = SensorManagerHeartRateSensor(appContext)

    @Singleton
    @Provides
    fun providesHrSender(
        @ApplicationContext appContext: Context,
        coroutineScopeProvider: Provider<CoroutineScope>
    ): HrSenderClient = if (useGrpc) {
        GrpcHrSenderClient(appContext, coroutineScopeProvider.get())
    } else {
        DataLayerHrSenderClient(appContext, coroutineScopeProvider.get())
    }
}