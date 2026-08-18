package com.metrom.app.garmin

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import com.metrom.shared.garmin.GarminProtocol
import com.metrom.shared.practice.MetronomeUiState
import java.util.HashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connect IQ companion: pushes metronome state to the watch and applies watch commands.
 * Fail-soft when Garmin Connect Mobile is missing — Metrom still runs without a watch.
 */
class GarminCompanion(
    context: Context,
    private val onCommand: (GarminProtocol.Command) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sendMutex = Mutex()
    private val watchApp = IQApp(GarminProtocol.APP_ID)
    private var connectIQ: ConnectIQ? = null
    private var collectJob: Job? = null
    private var ready = false
    private val devices = mutableListOf<IQDevice>()

    private val sdkListener = object : ConnectIQListener {
        override fun onSdkReady() {
            ready = true
            registerDevices()
        }

        override fun onInitializeError(status: IQSdkErrorStatus?) {
            ready = false
            Log.i(TAG, "Connect IQ unavailable: $status")
        }

        override fun onSdkShutDown() {
            ready = false
            devices.clear()
        }
    }

    private val deviceListener = IQDeviceEventListener { device, status ->
        remember(device)
        if (status == IQDevice.IQDeviceStatus.CONNECTED) {
            listenForMessages(device)
            sendTo(device, lastSnapshot)
        }
    }

    private val appListener = IQApplicationEventListener { _, _, message, _ ->
        val cmd = GarminProtocol.parseCommand(message) ?: return@IQApplicationEventListener
        if (cmd is GarminProtocol.Command.Sync) {
            broadcast(lastSnapshot)
        }
        onCommand(cmd)
    }

    @Volatile
    private var lastSnapshot: Map<String, Any> = emptyMap()

    fun bind(state: StateFlow<MetronomeUiState>) {
        if (connectIQ == null) {
            installGarminCrashGuard()
            // Debug + CIQ Simulator: TETHERED over adb :7381.
            // Release / real watch: WIRELESS through Garmin Connect Mobile.
            val kind = if (com.metrom.app.BuildConfig.DEBUG) {
                ConnectIQ.IQConnectType.TETHERED
            } else {
                ConnectIQ.IQConnectType.WIRELESS
            }
            val iq = ConnectIQ.getInstance(appContext, kind)
            connectIQ = iq
            try {
                iq.initialize(appContext, false, sdkListener)
            } catch (e: Exception) {
                Log.i(TAG, "Connect IQ init failed", e)
            }
        }
        collectJob?.cancel()
        collectJob = scope.launch {
            state
                .map { GarminProtocol.snapshot(it) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    lastSnapshot = snapshot
                    broadcast(snapshot)
                }
        }
    }

    fun dispose() {
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
        val iq = connectIQ ?: return
        try {
            iq.unregisterAllForEvents()
            iq.shutdown(appContext)
        } catch (_: InvalidStateException) {
        } catch (_: Exception) {
        }
        connectIQ = null
        ready = false
    }

    private fun registerDevices() {
        val iq = connectIQ ?: return
        devices.clear()
        val known = try {
            iq.knownDevices
        } catch (_: InvalidStateException) {
            emptyList()
        } catch (_: ServiceUnavailableException) {
            emptyList()
        } ?: emptyList()
        for (device in known) {
            remember(device)
            try {
                iq.registerForDeviceEvents(device, deviceListener)
            } catch (_: InvalidStateException) {
            }
            if (device.status == IQDevice.IQDeviceStatus.CONNECTED) {
                listenForMessages(device)
            }
        }
        broadcast(lastSnapshot)
    }

    private fun remember(device: IQDevice) {
        if (devices.none { it.deviceIdentifier == device.deviceIdentifier }) {
            devices.add(device)
        }
    }

    private fun listenForMessages(device: IQDevice) {
        val iq = connectIQ ?: return
        try {
            iq.registerForAppEvents(device, watchApp, appListener)
        } catch (_: InvalidStateException) {
        }
        // CIQ Simulator often delivers messages with an empty application id.
        if (com.metrom.app.BuildConfig.DEBUG) {
            try {
                iq.registerForAppEvents(device, IQApp(""), appListener)
            } catch (_: InvalidStateException) {
            }
        }
    }

    private fun broadcast(snapshot: Map<String, Any>) {
        if (!ready || snapshot.isEmpty()) return
        for (device in devices) {
            if (canSend(device)) {
                sendTo(device, snapshot)
            }
        }
    }

    private fun canSend(device: IQDevice): Boolean {
        if (device.status == IQDevice.IQDeviceStatus.CONNECTED) return true
        // TETHERED simulator often flips to UNKNOWN after the first packet.
        return com.metrom.app.BuildConfig.DEBUG
    }

    private fun sendTo(device: IQDevice, snapshot: Map<String, Any>) {
        if (snapshot.isEmpty()) return
        val iq = connectIQ ?: return
        val payload = HashMap<String, Any>(snapshot)
        // Garmin's TETHERED path writes a TCP socket. Device callbacks arrive on
        // the main thread; Android 17 throws NetworkOnMainThreadException there.
        // Serialize writes — overlapping sendMessage calls drop play/stop updates.
        scope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                try {
                    iq.sendMessage(device, watchApp, payload) { _, _, _ -> }
                } catch (_: InvalidStateException) {
                } catch (_: ServiceUnavailableException) {
                } catch (e: Exception) {
                    Log.w(TAG, "send failed", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GarminCompanion"

        @Volatile
        private var crashGuardInstalled = false

        private fun installGarminCrashGuard() {
            if (crashGuardInstalled) return
            crashGuardInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                if (isGarminAdbCrash(error)) {
                    Log.e(TAG, "Garmin ADB thread died", error)
                    return@setDefaultUncaughtExceptionHandler
                }
                previous?.uncaughtException(thread, error)
            }
        }

        private fun isGarminAdbCrash(error: Throwable): Boolean {
            return generateSequence(error) { it.cause }
                .flatMap { it.stackTrace.asSequence() }
                .any { it.className.startsWith("com.garmin.android.connectiq.adb") }
        }
    }
}
