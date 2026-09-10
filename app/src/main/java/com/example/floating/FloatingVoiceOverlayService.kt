package com.example.floating

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.service.FloatingVoiceTypingService

/**
 * Service managing the system overlay window (TYPE_APPLICATION_OVERLAY) for the draggable,
 * expandable floating voice typing button.
 */
class FloatingVoiceOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "FloatingOverlayService"

        fun start(context: Context) {
            val intent = Intent(context, FloatingVoiceOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingVoiceOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var composeView: View? = null
    private lateinit var floatingPrefs: FloatingPreferences
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        floatingPrefs = FloatingPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (canDrawOverlays()) {
            initOverlay()
        } else {
            Log.w(TAG, "Cannot draw overlays; permission not granted.")
            stopSelf()
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun initOverlay() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Initialize position from preferences or default to bottom-right
        val initialX = if (floatingPrefs.posX != FloatingPreferences.DEFAULT_COORDINATE) {
            floatingPrefs.posX
        } else {
            (screenWidth * 0.75f).toInt()
        }

        val initialY = if (floatingPrefs.posY != FloatingPreferences.DEFAULT_COORDINATE) {
            floatingPrefs.posY
        } else {
            (screenHeight * 0.65f).toInt()
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingVoiceOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingVoiceOverlayService)
            setContent {
                FloatingVoiceOverlay(
                    context = applicationContext,
                    onCloseClick = {
                        stopSelf()
                    },
                    onDragStart = {
                        handleDragStart()
                    },
                    onDrag = { dx, dy ->
                        handleDrag(dx, dy)
                    },
                    onDragEnd = {
                        handleDragEnd()
                    }
                )
            }
        }

        composeView = cv
        try {
            windowManager?.addView(cv, layoutParams)
            FloatingVoiceController.setOverlayActive(true)
            Log.i(TAG, "Overlay attached successfully at ($initialX, $initialY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay window", e)
        }
    }

    private var accumulatedDragX = 0f
    private var accumulatedDragY = 0f

    private fun handleDragStart() {
        accumulatedDragX = layoutParams.x.toFloat()
        accumulatedDragY = layoutParams.y.toFloat()
    }

    private fun handleDrag(dx: Float, dy: Float) {
        accumulatedDragX += dx
        accumulatedDragY += dy
        layoutParams.x = accumulatedDragX.toInt()
        layoutParams.y = accumulatedDragY.toInt()
        try {
            composeView?.let { cv ->
                windowManager?.updateViewLayout(cv, layoutParams)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay position during drag", e)
        }
    }

    private fun handleDragEnd() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val viewWidth = composeView?.width?.takeIf { it > 0 } ?: (56 * displayMetrics.density).toInt()
        val viewHeight = composeView?.height?.takeIf { it > 0 } ?: (56 * displayMetrics.density).toInt()

        val minX = 10
        val maxX = (screenWidth - viewWidth - 10).coerceAtLeast(minX)
        val minY = 50
        val maxY = (screenHeight - viewHeight - 50).coerceAtLeast(minY)

        val clampedX = layoutParams.x.coerceIn(minX, maxX)
        val clampedY = layoutParams.y.coerceIn(minY, maxY)

        layoutParams.x = clampedX
        layoutParams.y = clampedY
        accumulatedDragX = clampedX.toFloat()
        accumulatedDragY = clampedY.toFloat()

        try {
            composeView?.let { cv ->
                windowManager?.updateViewLayout(cv, layoutParams)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error snapping overlay on drag end", e)
        }
        floatingPrefs.savePosition(clampedX, clampedY)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        if (composeView != null && windowManager != null) {
            try {
                windowManager?.removeView(composeView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view", e)
            }
            composeView = null
        }

        FloatingVoiceController.setOverlayActive(false)
        FloatingVoiceTypingService.stopService(applicationContext)
        Log.i(TAG, "FloatingVoiceOverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
