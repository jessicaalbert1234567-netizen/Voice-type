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
import android.view.MotionEvent
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
import kotlin.math.abs

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
                    }
                )
            }
        }

        // Add touch listener to handle smooth drag & drop
        setupDragTouchListener(cv)

        composeView = cv
        try {
            windowManager?.addView(cv, layoutParams)
            FloatingVoiceController.setOverlayActive(true)
            Log.i(TAG, "Overlay attached successfully at ($initialX, $initialY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay window", e)
        }
    }

    private fun setupDragTouchListener(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false // Allow child clickables to receive down
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        // Only consider as drag if movement exceeds threshold
                        if (!isDragging && (abs(dx) > 12 || abs(dy) > 12)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(view, layoutParams)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error updating overlay layout", e)
                            }
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            // Snap near screen edges and save position
                            val displayMetrics = resources.displayMetrics
                            val maxX = displayMetrics.widthPixels - (view.width.takeIf { it > 0 } ?: 180)
                            val maxY = displayMetrics.heightPixels - (view.height.takeIf { it > 0 } ?: 180)

                            val clampedX = layoutParams.x.coerceIn(10, maxX.coerceAtLeast(10))
                            val clampedY = layoutParams.y.coerceIn(50, maxY.coerceAtLeast(50))

                            layoutParams.x = clampedX
                            layoutParams.y = clampedY
                            try {
                                windowManager?.updateViewLayout(view, layoutParams)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error snapping overlay", e)
                            }
                            floatingPrefs.savePosition(clampedX, clampedY)
                            return true
                        }
                    }
                }
                return false
            }
        })
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
