package com.littlekitty.xiaokele

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.chat.ChatController
import com.littlekitty.xiaokele.external.AndroidLocationFixProvider
import com.littlekitty.xiaokele.debug.DebugChannelModule
import com.littlekitty.xiaokele.debug.DebugExternalDataModule
import com.littlekitty.xiaokele.debug.DebugNotificationAdapter
import com.littlekitty.xiaokele.debug.DebugWeatherService
import com.littlekitty.xiaokele.di.AppModule
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DebugClassLocationTest {

    @Test
    fun debugActivityClassExistsOnDebugClasspath() {
        val classExists = runCatching {
            Class.forName("com.littlekitty.xiaokele.debug.DebugActivity")
        }.isSuccess
        assertTrue(classExists)
    }

    @Test
    fun debugChannelModuleUsesProductionNotificationAndWidgetBindings() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = startKoin {
            allowOverride(true)
            androidContext(context)
            modules(listOf(AppModule.module, DebugChannelModule.module, DebugExternalDataModule.module))
        }
        try {
            val notification = koin.koin.get<NotificationAdapter>()
            val widget = koin.koin.get<WidgetStateAdapter>()
            assertTrue(notification is com.littlekitty.xiaokele.notification.AndroidNotificationAdapter)
            assertTrue(widget is com.littlekitty.xiaokele.widget.AndroidWidgetStateAdapter)
        } finally {
            stopKoin()
        }
    }

    @Test
    fun productionChatGraphResolvesWithoutDependencyCycle() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = startKoin {
            allowOverride(true)
            androidContext(context)
            modules(listOf(AppModule.module, DebugChannelModule.module, DebugExternalDataModule.module))
        }
        try {
            // Resolving the activity's first lazy dependency exercises the
            // AppModelRouter -> local-tool graph that used to recurse through
            // ExternalDataCoordinator and crash with StackOverflowError.
            assertNotNull(koin.koin.get<ChatController>())
        } finally {
            stopKoin()
        }
    }

    @Test
    fun debugChannelFakesExistOnlyOnDebugClasspath() {
        val moduleExists = runCatching {
            Class.forName("com.littlekitty.xiaokele.debug.DebugChannelModule")
        }.isSuccess
        val fakeAdapterExists = runCatching {
            Class.forName("com.littlekitty.xiaokele.debug.DebugNotificationAdapter")
        }.isSuccess
        assertTrue(moduleExists)
        assertTrue(fakeAdapterExists)
    }

    @Test
    fun debugExternalDataModuleUsesRealProvidersAndDeviceLocation() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = startKoin {
            allowOverride(true)
            androidContext(context)
            modules(listOf(AppModule.module, DebugChannelModule.module, DebugExternalDataModule.module))
        }
        try {
            assertFalse(koin.koin.get<WeatherService>() is DebugWeatherService)
            assertTrue(koin.koin.get<WeatherService>() is com.littlekitty.xiaokele.core.network.external.QWeatherServiceAdapter)
            assertTrue(koin.koin.get<com.littlekitty.xiaokele.external.LocationFixProvider>() is AndroidLocationFixProvider)
        } finally {
            stopKoin()
        }
    }
}
