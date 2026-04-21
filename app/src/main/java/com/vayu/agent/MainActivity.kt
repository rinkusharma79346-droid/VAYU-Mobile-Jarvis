package com.vayu.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.vayu.agent.models.RecentTaskDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * VAYU MainActivity — THE FACE
 *
 * iOS 28-style Glassmorphism UI:
 * - Black background with blur glass cards
 * - Cyan accent throughout
 * - System status card (service, brain, task, steps, timer, current action)
 * - Task input with glass styling
 * - Recent tasks list
 * - Kill switch (always-visible red button)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BRAIN_URL = "http://localhost:8082"
        private const val OVERLAY_PERMISSION_CODE = 1001
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val recentTasks = mutableListOf<RecentTaskDisplay>()
    private lateinit var recentAdapter: RecentTasksAdapter

    // Views
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvBrainStatus: TextView
    private lateinit var tvTaskStatus: TextView
    private lateinit var tvStepCount: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvCurrentAction: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var etTaskInput: EditText
    private lateinit var btnExecute: TextView  // Using TextView for glass styling
    private lateinit var btnKillSwitch: ImageButton

    private var taskStartTime = 0L
    private var timerRunnable: Runnable? = null

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        setupRecyclerView()
        requestPermissions()

        // Start periodic status refresh
        startStatusPolling()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerRunnable?.let { uiHandler.removeCallbacks(it) }
        uiHandler.removeCallbacksAndMessages(null)
    }

    // ──────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────

    private fun initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvBrainStatus = findViewById(R.id.tvBrainStatus)
        tvTaskStatus = findViewById(R.id.tvTaskStatus)
        tvStepCount = findViewById(R.id.tvStepCount)
        tvTimer = findViewById(R.id.tvTimer)
        tvCurrentAction = findViewById(R.id.tvCurrentAction)
        progressBar = findViewById(R.id.progressBar)
        etTaskInput = findViewById(R.id.etTaskInput)
        btnExecute = findViewById(R.id.btnExecute)
        btnKillSwitch = findViewById(R.id.btnKillSwitch)
    }

    private fun setupClickListeners() {
        // Execute button — submit task
        btnExecute.setOnClickListener {
            val task = etTaskInput.text.toString().trim()
            if (task.isEmpty()) {
                Toast.makeText(this, "Enter a task first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitTask(task)
            etTaskInput.text.clear()
        }

        // Kill switch — abort everything
        btnKillSwitch.setOnClickListener {
            VayuService.abort()
            Toast.makeText(this, "Task aborted", Toast.LENGTH_SHORT).show()
            updateTaskStatus("ABORTED", 0, "—")
        }

        // Long press kill switch for dramatic effect
        btnKillSwitch.setOnLongClickListener {
            VayuService.abort()
            Toast.makeText(this, "EMERGENCY STOP", Toast.LENGTH_LONG).show()
            updateTaskStatus("ABORTED", 0, "—")
            true
        }
    }

    private fun setupRecyclerView() {
        recentAdapter = RecentTasksAdapter(recentTasks)
        val rvRecent = findViewById<RecyclerView>(R.id.rvRecentTasks)
        rvRecent.layoutManager = LinearLayoutManager(this)
        rvRecent.adapter = recentAdapter
    }

    // ──────────────────────────────────────────────
    // Permissions
    // ──────────────────────────────────────────────

    private fun requestPermissions() {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
        }

        // Check accessibility service
        if (!isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Enable VAYU in Accessibility Settings",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(packageName)
    }

    // ──────────────────────────────────────────────
    // Task Submission
    // ──────────────────────────────────────────────

    private fun submitTask(description: String) {
        // Submit to brain.py — MUST run on IO dispatcher
        lifecycleScope.launch {
            try {
                val json = gson.toJson(mapOf(
                    "id" to "task_${System.currentTimeMillis()}",
                    "description" to description
                ))
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BRAIN_URL/task/submit")
                    .post(body)
                    .build()

                // HTTP call on IO thread — NOT main thread
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().close()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Task submitted to VAYU", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Brain offline — start brain.py in Termux",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Add to recent tasks
        recentTasks.add(0, RecentTaskDisplay(
            description = description,
            status = "PENDING",
            steps = 0,
            duration = "—"
        ))
        recentAdapter.notifyItemInserted(0)

        // Start HUD service
        startService(Intent(this, HUDService::class.java))

        taskStartTime = System.currentTimeMillis()
        startTimer()
    }

    // ──────────────────────────────────────────────
    // Status Polling
    // ──────────────────────────────────────────────

    private fun startStatusPolling() {
        val pollRunnable = object : Runnable {
            override fun run() {
                refreshStatus()
                uiHandler.postDelayed(this, 3000)  // Every 3 seconds
            }
        }
        uiHandler.postDelayed(pollRunnable, 1000)
    }

    private fun refreshStatus() {
        // Service status
        val serviceOnline = isAccessibilityEnabled()
        tvServiceStatus.text = if (serviceOnline) "ONLINE" else "OFFLINE"
        tvServiceStatus.setTextColor(
            if (serviceOnline) getColor(R.color.green_online) else getColor(R.color.red_kill)
        )

        // Brain status — HTTP call MUST be on IO dispatcher
        lifecycleScope.launch {
            try {
                val request = Request.Builder()
                    .url("$BRAIN_URL/status")
                    .get()
                    .build()

                // Execute on IO thread — prevents NetworkOnMainThreadException
                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        tvBrainStatus.text = "ONLINE"
                        tvBrainStatus.setTextColor(getColor(R.color.green_online))
                    } else {
                        tvBrainStatus.text = "OFFLINE"
                        tvBrainStatus.setTextColor(getColor(R.color.red_kill))
                    }
                }
                response.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvBrainStatus.text = "OFFLINE"
                    tvBrainStatus.setTextColor(getColor(R.color.red_kill))
                }
            }
        }

        // Current task status from VayuService
        if (VayuService.isRunning) {
            val task = VayuService.currentTask
            updateTaskStatus(
                task?.status ?: "RUNNING",
                VayuService.currentStep,
                VayuService.lastAction
            )
        }
    }

    // ──────────────────────────────────────────────
    // UI Updates
    // ──────────────────────────────────────────────

    private fun updateTaskStatus(status: String, steps: Int, action: String) {
        tvTaskStatus.text = status
        tvTaskStatus.setTextColor(
            when (status) {
                "RUNNING" -> getColor(R.color.cyan_primary)
                "DONE" -> getColor(R.color.green_online)
                "FAILED" -> getColor(R.color.red_kill)
                "ABORTED" -> getColor(R.color.yellow_warning)
                else -> getColor(R.color.text_tertiary)
            }
        )

        tvStepCount.text = "$steps / 50"
        tvCurrentAction.text = action

        progressBar.progress = steps

        // Animate progress bar
        ObjectAnimator.ofInt(progressBar, "progress", steps).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    // ──────────────────────────────────────────────
    // Timer
    // ──────────────────────────────────────────────

    private fun startTimer() {
        timerRunnable?.let { uiHandler.removeCallbacks(it) }
        timerRunnable = object : Runnable {
            override fun run() {
                if (VayuService.isRunning) {
                    val elapsed = System.currentTimeMillis() - taskStartTime
                    val minutes = (elapsed / 1000) / 60
                    val seconds = (elapsed / 1000) % 60
                    tvTimer.text = String.format("%02d:%02d", minutes, seconds)
                    uiHandler.postDelayed(this, 1000)
                }
            }
        }
        uiHandler.post(timerRunnable!!)
    }

    // ──────────────────────────────────────────────
    // Recent Tasks Adapter
    // ──────────────────────────────────────────────

    inner class RecentTasksAdapter(
        private val tasks: List<RecentTaskDisplay>
    ) : RecyclerView.Adapter<RecentTasksAdapter.TaskViewHolder>() {

        inner class TaskViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val tvDesc: TextView = view.findViewById(android.R.id.text1)
            val tvStatus: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TaskViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return TaskViewHolder(view)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val task = tasks[position]
            holder.tvDesc.text = task.description
            holder.tvDesc.setTextColor(getColor(R.color.text_primary))
            holder.tvStatus.text = "${task.status} · ${task.steps} steps · ${task.duration}"
            holder.tvStatus.setTextColor(getColor(R.color.text_secondary))
        }

        override fun getItemCount() = tasks.size
    }
}
