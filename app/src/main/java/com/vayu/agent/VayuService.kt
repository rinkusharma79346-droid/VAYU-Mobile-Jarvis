package com.vayu.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.vayu.agent.models.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * VAYU Service — THE HANDS
 *
 * Android AccessibilityService that controls the phone without root.
 * Captures screen, reads UI tree, polls brain.py for decisions,
 * and executes TAP/SWIPE/TYPE/SCROLL/OPEN_APP/PRESS_BACK/PRESS_HOME actions.
 *
 * Threading model:
 * - Coroutine scope on Dispatchers.IO for network & heavy work
 * - Switch to Dispatchers.Main for Accessibility API calls (screenshot, UI tree, gestures)
 * - Handler on main looper for screenshot callbacks
 */
class VayuService : AccessibilityService() {

    companion object {
        private const val TAG = "VayuService"
        private const val BRAIN_URL = "http://127.0.0.1:8082"
        private const val MAX_STEPS = 50
        private const val STEP_DELAY_MS = 600L       // 0.6s per step (Jarvis speed)
        private const val SAME_SCREEN_THRESHOLD = 3   // Retry threshold before auto-recovery
        private const val SCROLL_RETRIES = 3          // Max scroll retries when element not found

        @Volatile var isRunning = false
            private set
        @Volatile var currentTask: VayuTask? = null
            private set
        @Volatile var currentStep = 0
            private set
        @Volatile var lastAction: String = "—"
            private set
        @Volatile var shouldAbort = false
            private set

        fun abort() {
            shouldAbort = true
        }
    }

    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<HistoryItem>()
    private var previousScreenHash = ""
    private var sameScreenCount = 0
    private var taskStartTime = 0L

    // ──────────────────────────────────────────────
    // Accessibility Service Lifecycle
    // ──────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "VayuService connected — THE HANDS are ready")
        Toast.makeText(this, "VAYU Service Active", Toast.LENGTH_SHORT).show()

        // Start polling for pending tasks
        scope.launch {
            pollForTasks()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to events — we drive the loop ourselves
    }

    override fun onInterrupt() {
        Log.w(TAG, "VayuService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "VayuService destroyed")
    }

    // ──────────────────────────────────────────────
    // Task Polling — Checks brain.py for new tasks
    // ──────────────────────────────────────────────

    private suspend fun pollForTasks() {
        Log.i(TAG, "Task polling started")
        while (scope.coroutineContext.isActive) {
            if (!isRunning && !shouldAbort) {
                try {
                    val task = checkPendingTask()
                    if (task != null) {
                        Log.i(TAG, "Received task: ${task.description}")
                        startTask(task)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Poll error: ${e.message}")
                }
            }
            delay(2000L) // Poll every 2 seconds
        }
    }

    private fun checkPendingTask(): VayuTask? {
        return try {
            val request = Request.Builder()
                .url("$BRAIN_URL/task/pending")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val json = JsonParser.parseString(body).asJsonObject
                    val hasTask = json.has("has_task") && json.get("has_task").asBoolean
                    if (hasTask && json.has("task") && !json.get("task").isJsonNull) {
                        val taskObj = json.get("task").asJsonObject
                        val desc = taskObj.get("description")?.asString ?: return null
                        val id = taskObj.get("id")?.asString ?: "task_${System.currentTimeMillis()}"
                        VayuTask(id = id, description = desc)
                    } else null
                } else {
                    Log.w(TAG, "Pending task check failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Brain not reachable: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────
    // Submit Task (called from MainActivity)
    // ──────────────────────────────────────────────

    fun submitTask(description: String) {
        val task = VayuTask(
            id = "task_${System.currentTimeMillis()}",
            description = description
        )
        scope.launch { startTask(task) }
    }

    // ──────────────────────────────────────────────
    // ReAct Loop — The Core Agent Loop
    // ──────────────────────────────────────────────

    private suspend fun startTask(task: VayuTask) {
        if (isRunning) {
            Log.w(TAG, "Already running a task, skipping: ${task.description}")
            return
        }

        isRunning = true
        shouldAbort = false
        currentTask = task.copy(status = "RUNNING")
        currentStep = 0
        lastAction = "Starting..."
        history.clear()
        previousScreenHash = ""
        sameScreenCount = 0
        taskStartTime = System.currentTimeMillis()

        Log.i(TAG, "═══ STARTING TASK: ${task.description} ═══")

        // Task was already submitted to brain by MainActivity
        // Do NOT re-submit — that creates duplicates in the queue

        // Notify HUD
        notifyHUD("STARTED", 0, task.description)

        // Give the system a moment to settle before we start capturing screens
        delay(1000L)

        // Track consecutive screenshot failures
        var screenshotFailCount = 0

        try {
            while (currentStep < MAX_STEPS && !shouldAbort) {
                currentStep++
                lastAction = "Step $currentStep: Analyzing..."

                // 1. Capture screenshot (Main thread required for Accessibility API)
                val screenshot = captureScreen()
                if (screenshot == null) {
                    screenshotFailCount++
                    Log.e(TAG, "Step $currentStep: Failed to capture screenshot ($screenshotFailCount consecutive failures)")
                    if (screenshotFailCount >= 3) {
                        // 3 consecutive screenshot failures — fail gracefully
                        Log.e(TAG, "3 consecutive screenshot failures — cannot proceed")
                        handler.post {
                            Toast.makeText(this@VayuService, "VAYU: Cannot capture screen — check accessibility permission", Toast.LENGTH_LONG).show()
                        }
                        completeTask(task, "FAILED", "Cannot capture screen — accessibility permission may be missing")
                        return
                    }
                    delay(STEP_DELAY_MS)
                    continue
                }
                screenshotFailCount = 0  // Reset on success
                Log.d(TAG, "Step $currentStep: Screenshot captured (${screenshot.length} chars)")

                // 2. Read UI tree (Main thread required for Accessibility API)
                val uiTree = readUITree()
                Log.d(TAG, "Step $currentStep: UI tree read (${uiTree.length} chars)")

                // 3. Check for same screen (stuck detection)
                val screenHash = (screenshot.take(200) + uiTree.take(200)).hashCode().toString()
                if (screenHash == previousScreenHash) {
                    sameScreenCount++
                    if (sameScreenCount >= SAME_SCREEN_THRESHOLD) {
                        Log.w(TAG, "Stuck on same screen for $sameScreenCount steps — auto-recovering")
                        withContext(Dispatchers.Main) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        sameScreenCount = 0
                        delay(STEP_DELAY_MS)
                        continue
                    }
                } else {
                    sameScreenCount = 0
                    previousScreenHash = screenHash
                }

                // 4. Ask the brain what to do
                val brainAction = askBrain(task.description, screenshot, uiTree)
                if (brainAction == null) {
                    Log.e(TAG, "Step $currentStep: Brain returned null — retrying in 1s")
                    delay(1000L)
                    continue
                }

                // 5. Log the action
                lastAction = formatAction(brainAction)
                Log.i(TAG, "Step $currentStep: $lastAction — ${brainAction.reason}")

                // 6. Add to history
                history.add(HistoryItem(
                    step = currentStep,
                    action = brainAction.action,
                    description = lastAction,
                    result = ""
                ))

                // 7. Execute the action (gestures need Main thread dispatch)
                val actionResult = withContext(Dispatchers.Main) {
                    executeAction(brainAction)
                }

                // 8. Update history with result
                if (history.isNotEmpty()) {
                    history[history.lastIndex] = history.last().copy(result = actionResult)
                }

                // 9. Notify HUD
                notifyHUD(brainAction.action, currentStep, lastAction)

                // 10. Check if task is complete
                if (brainAction.action == "DONE") {
                    completeTask(task, "DONE", brainAction.reason)
                    return
                }
                if (brainAction.action == "FAIL") {
                    completeTask(task, "FAILED", brainAction.reason)
                    return
                }

                // 11. Wait before next step
                delay(STEP_DELAY_MS)
            }

            // Max steps reached
            if (currentStep >= MAX_STEPS) {
                completeTask(task, "FAILED", "Max steps ($MAX_STEPS) reached")
            }
            if (shouldAbort) {
                completeTask(task, "ABORTED", "User aborted")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Task error", e)
            completeTask(task, "FAILED", e.message ?: "Unknown error")
        }
    }

    // ──────────────────────────────────────────────
    // Brain Communication
    // ──────────────────────────────────────────────

    private fun askBrain(task: String, screenshot: String, uiTree: String): BrainAction? {
        return try {
            // Read memory from brain
            val memory = readMemory()

            val request = ActRequest(
                task = task,
                step = currentStep,
                screenshot = screenshot,
                uiTree = uiTree,
                history = history.takeLast(10),  // Last 10 steps for context
                memory = memory
            )

            val json = gson.toJson(request)
            Log.d(TAG, "Sending to brain: ${json.take(200)}...")
            val httpRequest = Request.Builder()
                .url("$BRAIN_URL/act")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    Log.d(TAG, "Brain response: ${body.take(300)}")
                    val jsonResp = JsonParser.parseString(body).asJsonObject
                    if (jsonResp.has("action")) {
                        gson.fromJson(jsonResp.get("action"), BrainAction::class.java)
                    } else null
                } else {
                    Log.e(TAG, "Brain error: ${response.code} — ${response.body?.string()?.take(200)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Brain communication failed: ${e.message}")
            null
        }
    }

    private fun readMemory(): Map<String, String> {
        return try {
            val request = Request.Builder()
                .url("$BRAIN_URL/memory")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyMap()
                    val json = JsonParser.parseString(body).asJsonObject
                    val memory = mutableMapOf<String, String>()
                    if (json.has("memory")) {
                        json.get("memory").asJsonObject.entrySet().forEach { (key, value) ->
                            memory[key] = value.toString()
                        }
                    }
                    memory
                } else emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun submitToBrain(task: VayuTask) {
        try {
            val json = gson.toJson(mapOf("task" to task))
            val request = Request.Builder()
                .url("$BRAIN_URL/task/submit")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().close()
            Log.d(TAG, "Task submitted to brain: ${task.description}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to submit task to brain: ${e.message}")
        }
    }

    private fun reportResult(task: VayuTask, status: String, result: String) {
        try {
            val taskResult = TaskResult(
                taskId = task.id,
                status = status,
                stepsCompleted = currentStep,
                result = result
            )
            val json = gson.toJson(taskResult)
            val request = Request.Builder()
                .url("$BRAIN_URL/task/result")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().close()
            Log.d(TAG, "Task result reported: $status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report result to brain: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Screen Capture — Must run on Main thread
    // ──────────────────────────────────────────────

    private suspend fun captureScreen(): String? {
        return withContext(Dispatchers.Main) {
            try {
                var resultBitmap: Bitmap? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    { runnable -> handler.post(runnable) },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            resultBitmap = result.hardwareBuffer?.let { hb ->
                                Bitmap.wrapHardwareBuffer(hb, null)
                            }
                            latch.countDown()
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot error code: $errorCode")
                            latch.countDown()
                        }
                    }
                )

                // Wait up to 2 seconds for screenshot callback
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                val hwBitmap = resultBitmap ?: run {
                    Log.e(TAG, "Screenshot: bitmap was null after capture")
                    return@withContext null
                }

                // Convert hardware bitmap to software bitmap for compression
                val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: run {
                    Log.e(TAG, "Screenshot: failed to copy hardware bitmap")
                    return@withContext null
                }
                hwBitmap.recycle()

                // Compress on IO thread to avoid blocking Main
                withContext(Dispatchers.IO) {
                    try {
                        val stream = ByteArrayOutputStream()
                        val halfW = (bitmap.width / 2).coerceAtLeast(1)
                        val halfH = (bitmap.height / 2).coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bitmap, halfW, halfH, true)
                        scaled.compress(Bitmap.CompressFormat.PNG, 70, stream)
                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        bitmap.recycle()
                        if (scaled !== bitmap) scaled.recycle()
                        Log.d(TAG, "Screenshot compressed: ${base64.length} chars base64")
                        base64
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot compression failed: ${e.message}")
                        bitmap.recycle()
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot capture failed: ${e.message}")
                null
            }
        }
    }

    // ──────────────────────────────────────────────
    // UI Tree Reading — Must run on Main thread
    // ──────────────────────────────────────────────

    private suspend fun readUITree(): String {
        return withContext(Dispatchers.Main) {
            try {
                val rootNode = rootInActiveWindow ?: return@withContext "[]"
                val nodes = mutableListOf<UINode>()
                traverseNode(rootNode, nodes, 0)
                gson.toJson(nodes)
            } catch (e: Exception) {
                Log.e(TAG, "UI tree read failed: ${e.message}")
                "[]"
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<UINode>, depth: Int) {
        if (depth > 15) return  // Limit tree depth

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val uiNode = UINode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            bounds = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            focusable = node.isFocusable,
            checked = if (node.isCheckable) node.isChecked else null,
            resourceId = node.viewIdResourceName ?: "",
            packageName = node.packageName?.toString() ?: "",
            depth = depth
        )

        // Only include nodes with useful info
        if (uiNode.text.isNotEmpty() || uiNode.clickable || uiNode.scrollable ||
            uiNode.editable || uiNode.contentDescription.isNotEmpty() ||
            uiNode.resourceId.isNotEmpty()
        ) {
            list.add(uiNode)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, list, depth + 1)
        }
    }

    // ──────────────────────────────────────────────
    // Action Execution — The Gesture Engine
    // ──────────────────────────────────────────────

    private fun executeAction(action: BrainAction): String {
        return when (action.action) {
            "TAP" -> executeTap(action.x.toFloat(), action.y.toFloat())
            "SWIPE" -> executeSwipe(
                action.x.toFloat(), action.y.toFloat(),
                action.x2.toFloat(), action.y2.toFloat()
            )
            "TYPE" -> executeType(action.text)
            "SCROLL" -> executeScroll(action.direction)
            "OPEN_APP" -> executeOpenApp(action.app)
            "PRESS_BACK" -> executePressBack()
            "PRESS_HOME" -> executePressHome()
            "DONE" -> "Task completed"
            "FAIL" -> "Task failed"
            else -> "Unknown action: ${action.action}"
        }
    }

    private fun executeTap(x: Float, y: Float): String {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
        return "TAP ($x, $y)"
    }

    private fun executeSwipe(x1: Float, y1: Float, x2: Float, y2: Float): String {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
        return "SWIPE ($x1,$y1) → ($x2,$y2)"
    }

    private fun executeType(text: String): String {
        // Strategy: Find focused editable field, set text via accessibility
        val focusNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusNode != null && focusNode.isEditable) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusNode.recycle()
            return "TYPE \"$text\""
        }

        // Fallback: use clipboard paste
        if (focusNode != null) {
            val clip = android.content.ClipData.newPlainText("vayu", text)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(clip)
            focusNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            focusNode.recycle()
            return "TYPE (paste) \"$text\""
        }

        return "TYPE failed: no focused input"
    }

    private fun executeScroll(direction: String): String {
        val screenRect = Rect()
        rootInActiveWindow?.getBoundsInScreen(screenRect) ?: return "SCROLL failed"

        val cx = screenRect.exactCenterX()
        val cy = screenRect.exactCenterY()
        val offset = screenRect.height() * 0.35f

        val (startY, endY) = when (direction.uppercase()) {
            "UP" -> cy - offset to cy + offset
            "DOWN" -> cy + offset to cy - offset
            else -> cy + offset to cy - offset  // Default: scroll down
        }

        val (startX, endX) = when (direction.uppercase()) {
            "LEFT" -> cx - offset to cx + offset
            "RIGHT" -> cx + offset to cx - offset
            else -> cx to cx
        }

        return executeSwipe(startX, startY, endX, endY)
    }

    private fun executeOpenApp(packageName: String): String {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                "OPEN_APP $packageName"
            } else {
                "OPEN_APP failed: $packageName not found"
            }
        } catch (e: Exception) {
            "OPEN_APP error: ${e.message}"
        }
    }

    private fun executePressBack(): String {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return "PRESS_BACK"
    }

    private fun executePressHome(): String {
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "PRESS_HOME"
    }

    // ──────────────────────────────────────────────
    // Task Completion
    // ──────────────────────────────────────────────

    private fun completeTask(task: VayuTask, status: String, result: String) {
        val elapsed = System.currentTimeMillis() - taskStartTime

        currentTask = task.copy(
            status = status,
            steps = currentStep,
            endTime = System.currentTimeMillis(),
            result = result
        )

        lastAction = when (status) {
            "DONE" -> "Task completed"
            "FAILED" -> "Task failed: $result"
            "ABORTED" -> "Task aborted"
            else -> result
        }

        Log.i(TAG, "═══ TASK $status ═══ $result (${elapsed}ms, $currentStep steps)")

        // Report to brain
        reportResult(task, status, result)

        // Notify HUD to hide
        notifyHUD("DONE", currentStep, lastAction)

        // Toast
        handler.post {
            Toast.makeText(this, "VAYU: $lastAction", Toast.LENGTH_LONG).show()
        }

        // Reset state
        isRunning = false
        currentStep = 0
        shouldAbort = false
    }

    // ──────────────────────────────────────────────
    // HUD Communication
    // ──────────────────────────────────────────────

    private fun notifyHUD(action: String, step: Int, description: String) {
        val intent = Intent("com.vayu.agent.HUD_UPDATE")
        intent.setPackage(packageName)
        intent.putExtra("action", action)
        intent.putExtra("step", step)
        intent.putExtra("max_steps", MAX_STEPS)
        intent.putExtra("description", description)
        sendBroadcast(intent)
    }

    // ──────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────

    private fun formatAction(action: BrainAction): String {
        return when (action.action) {
            "TAP" -> "TAP (${action.x}, ${action.y})"
            "SWIPE" -> "SWIPE (${action.x},${action.y})→(${action.x2},${action.y2})"
            "TYPE" -> "TYPE \"${action.text.take(30)}\""
            "SCROLL" -> "SCROLL ${action.direction}"
            "OPEN_APP" -> "OPEN ${action.app}"
            "PRESS_BACK" -> "PRESS_BACK"
            "PRESS_HOME" -> "PRESS_HOME"
            "DONE" -> "DONE"
            "FAIL" -> "FAIL"
            else -> action.action
        }
    }

}
