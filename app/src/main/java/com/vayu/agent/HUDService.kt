package com.vayu.agent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vayu.agent.models.VayuTask

/**
 * HUD Service — Manages the floating PiP overlay.
 *
 * The overlay appears when VAYU starts working and the user exits the app.
 * Shows live progress: step counter, progress bar, last action, STOP button.
 * Draggable anywhere. Auto-hides when task completes.
 */
class HUDService : Service() {

    companion object {
        private const val TAG = "HUDService"
        private const val CHANNEL_ID = "vayu_hud"
        private const val NOTIFICATION_ID = 2001
    }

    private var windowManager: WindowManager? = null
    private var hudView: View? = null
    private var isHudVisible = false

    private lateinit var tvStep: TextView
    private lateinit var tvAction: TextView
    private lateinit var tvStop: TextView
    private lateinit var pbProgress: ProgressBar

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.vayu.agent.HUD_UPDATE" -> {
                    val action = intent.getStringExtra("action") ?: return
                    val step = intent.getIntExtra("step", 0)
                    val maxSteps = intent.getIntExtra("max_steps", 50)
                    val description = intent.getStringExtra("description") ?: ""

                    if (action == "STARTED" || action in listOf(
                            "TAP", "SWIPE", "TYPE", "SCROLL",
                            "OPEN_APP", "PRESS_BACK", "PRESS_HOME"
                        )
                    ) {
                        showHUD()
                        updateHUD(step, maxSteps, description)
                    } else if (action == "DONE") {
                        updateHUD(step, maxSteps, description)
                        // Auto-hide after 2 seconds
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            hideHUD()
                        }, 2000)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Register receiver for HUD updates
        val filter = IntentFilter("com.vayu.agent.HUD_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hudReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(hudReceiver, filter)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(hudReceiver) } catch (_: Exception) {}
        hideHUD()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────
    // HUD Visibility
    // ──────────────────────────────────────────────

    @SuppressLint("InflateParams")
    private fun showHUD() {
        if (isHudVisible) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
            width = (260 * resources.displayMetrics.density).toInt()
            height = (80 * resources.displayMetrics.density).toInt()
        }

        hudView = createHUDView()
        windowManager?.addView(hudView, params)
        isHudVisible = true
    }

    private fun hideHUD() {
        if (!isHudVisible) return
        try {
            hudView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        hudView = null
        isHudVisible = false
    }

    private fun updateHUD(step: Int, maxSteps: Int, description: String) {
        if (!isHudVisible) return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tvStep.text = "Step $step/$maxSteps"
                tvAction.text = description
                pbProgress.max = maxSteps
                pbProgress.progress = step
            }
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────
    // HUD View Construction — Glass Design
    // ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createHUDView(): View {
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_hud)
            setPadding(
                (14 * density).toInt(),
                (10 * density).toInt(),
                (14 * density).toInt(),
                (10 * density).toInt()
            )
        }

        // Inner layout
        val innerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top row: step + stop
        val topRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        tvStep = TextView(this).apply {
            text = "Step 1/50"
            setTextColor(android.graphics.Color.parseColor("#FF00E5FF"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        tvStop = TextView(this).apply {
            text = "STOP"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(
                (8 * density).toInt(), (3 * density).toInt(),
                (8 * density).toInt(), (3 * density).toInt()
            )
            setBackgroundResource(R.drawable.bg_kill_switch)
            setOnClickListener {
                VayuService.abort()
            }
        }

        topRow.addView(tvStep)
        topRow.addView(tvStop)

        // Action text
        tvAction = TextView(this).apply {
            text = "Starting..."
            setTextColor(android.graphics.Color.parseColor("#B3FFFFFF"))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Progress bar
        pbProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 50
            progress = 0
            progressDrawable = resources.getDrawable(R.drawable.bg_progress_bar, null)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (3 * density).toInt()
            ).apply {
                topMargin = (4 * density).toInt()
            }
        }

        innerLayout.addView(topRow)
        innerLayout.addView(tvAction)
        innerLayout.addView(pbProgress)
        container.addView(innerLayout)

        // ── Drag handling ──
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            val params = hudView?.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(hudView!!, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }

        return container
    }

    // ──────────────────────────────────────────────
    // Foreground Notification
    // ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VAYU HUD",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VAYU floating HUD service"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VAYU Active")
            .setContentText("Agent is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
