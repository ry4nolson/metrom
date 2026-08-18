package com.metrom.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrom.app.ui.MetronomeScreen
import com.metrom.app.ui.theme.MetromTheme

class MainActivity : ComponentActivity() {
    /**
     * Activity-scoped ViewModel owns the engine. Survives backgrounding; portrait
     * lock avoids rotation recreates. Process death tears everything down together.
     */
    private val viewModel: MetronomeViewModel by viewModels()

    /** Once per process — never block play/stop on the result. */
    private var notificationPrompted = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Granted → FGS notification visible. Denied → metronome keeps running;
        // PlaybackService.sync already swallows notification/FGS start failures.
        notificationPrompted = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val colorTheme by viewModel.theme.collectAsStateWithLifecycle()
            MetromTheme(theme = colorTheme) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                // Ask on first play so cold launch isn't a permission wall.
                LaunchedEffect(state.isPlaying) {
                    if (state.isPlaying) maybeRequestNotificationPermission()
                }
                // Only while resumed — leaving the screen on overnight with the
                // pendulum animating will melt an emulator / trip system ANRs.
                KeepScreenOnWhileResumed(enabled = state.isPlaying)
                // Mic listen is foreground-only — release on pause.
                CancelListenOnPause(viewModel = viewModel)
                MetronomeScreen(viewModel = viewModel)
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPrompted) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            notificationPrompted = true
            return
        }
        notificationPrompted = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        // Backgrounding does not finish the activity — keep the beat + FGS alive.
        // Explicit exit / back-finish stops transport before the ViewModel clears.
        if (isFinishing) {
            viewModel.stop()
        }
        super.onDestroy()
    }
}

@Composable
private fun CancelListenOnPause(viewModel: MetronomeViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onListenLifecyclePause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun KeepScreenOnWhileResumed(enabled: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(enabled, lifecycleOwner) {
        val window = (view.context as? ComponentActivity)?.window
        fun apply(keepOn: Boolean) {
            if (keepOn) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        if (!enabled) {
            apply(false)
            return@DisposableEffect onDispose { apply(false) }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply(true)
                Lifecycle.Event.ON_PAUSE -> apply(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        apply(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            apply(false)
        }
    }
}
