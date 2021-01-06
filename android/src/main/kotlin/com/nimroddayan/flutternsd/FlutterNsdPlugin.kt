/*
 * Copyright 2020 Nimrod Dayan nimroddayan.com
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.nimroddayan.flutternsd

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat.getSystemService

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import timber.log.Timber
import java.lang.Exception

/** FlutterNsdPlugin */
class FlutterNsdPlugin : FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    private var nsdManager: NsdManager? = null

    private lateinit var serviceType: String
    private lateinit var mainHandler: Handler

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        nsdManager = getSystemService(flutterPluginBinding.applicationContext, NsdManager::class.java)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.nimroddayan/flutter_nsd")
        mainHandler = Handler(Looper.getMainLooper())
        channel.setMethodCallHandler(this)
        Timber.d("Plugin initialized successfully")
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "com.nimroddayan/flutter_nsd")
            channel.setMethodCallHandler(FlutterNsdPlugin())
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (nsdManager == null) {
            result.error("1000", "NsdManager not initialized", null)
        }

        Timber.d("Method called: ${call.method}")
        when (call.method) {
            "startDiscovery" -> {
                val serviceType = try {
                    call.argument<String>("serviceType")
                } catch (ex: Exception) {
                    result.error("1002", "service type must be a string", null)
                    return
                }

                if (serviceType == null) {
                    result.error("1001", "service type cannot be null", null)
                    return
                }

                startDiscovery(serviceType)
                result.success(null)
            }
            "stopDiscovery" -> {
                stopDiscovery()
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    private fun startDiscovery(serviceType: String) {
        Timber.d("Staring NSD for service type: $serviceType")
        this.serviceType = serviceType
        try {
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (ex: Exception) {
            Timber.w("Cannot start, NSD is already running")
        }
    }

    private fun stopDiscovery() {
        Timber.d("Stopping NSD")
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (ex: Exception) {
            Timber.w("Cannot stop NSD when it's not started")
        }
    }

    // Instantiate a new DiscoveryListener
    private val discoveryListener = object : NsdManager.DiscoveryListener {

        // Called as soon as service discovery begins.
        override fun onDiscoveryStarted(regType: String) {
            Timber.d("NSD started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Timber.d("Service found serviceName: ${service.serviceName} serviceType: ${service.serviceType}")
            if (serviceType == service.serviceType) {
                Timber.d("Resolving service $service")
                try {
                    nsdManager?.resolveService(service, resolveListener)
                } catch (e: Exception) {
                    Timber.w("Cannot resolve service, service resolve in progress")
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Timber.v("Service lost $service")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Timber.d("NSD stopped")
            mainHandler.post {
                channel.invokeMethod("onDiscoveryStopped", null)
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.w("Failed to start NSD. Error code $errorCode")
            nsdManager?.stopServiceDiscovery(this)
            mainHandler.post {
                channel.invokeMethod("onStartDiscoveryFailed", errorCode)
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.w("Failed to stop NSD. Error code $errorCode")
            nsdManager?.stopServiceDiscovery(this)
            mainHandler.post {
                channel.invokeMethod("onStopDiscoveryFailed", errorCode)
            }
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            Timber.w("Failed to resolve service $serviceInfo error code: $errorCode")
            mainHandler.post {
                channel.invokeMethod("onResolveFailed", errorCode)
            }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
            serviceInfo?.let { serviceInfo ->
                val hostname = serviceInfo.host?.canonicalHostName
                val port = serviceInfo.port
                val name = serviceInfo.serviceName
                val txt = serviceInfo.getAttributes()

                if (hostname != null && port != null) {
                    Timber.v("Found service on: $hostname:$port")
                    val result = mapOf<String, Any?>(
                        "hostname" to hostname,
                        "port" to port,
                        "name" to name,
                        "txt" to txt
                    )
                    mainHandler.post {
                        channel.invokeMethod("onServiceResolved", result)
                    }
                }
            }
        }
    }
}
