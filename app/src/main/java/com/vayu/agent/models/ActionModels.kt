package com.vayu.agent.models

import com.google.gson.annotations.SerializedName

/**
 * Action returned by the brain (Gemini) after analyzing the screen.
 * The brain decides what to do next; VayuService executes it.
 */
data class BrainAction(
    val action: String,           // TAP | SWIPE | TYPE | SCROLL | OPEN_APP | PRESS_BACK | PRESS_HOME | DONE | FAIL
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = "",
    val direction: String = "",   // UP | DOWN | LEFT | RIGHT (for SCROLL)
    val app: String = "",         // Package name for OPEN_APP
    val reason: String = "",      // Brain's reasoning for this step
    val confidence: Float = 0f
)

/**
 * Request body sent to brain.py /act endpoint.
 */
data class ActRequest(
    val task: String,
    val step: Int,
    val screenshot: String,       // Base64 encoded PNG
    val uiTree: String,           // Serialized UI tree as JSON string
    val history: List<HistoryItem>,
    val memory: Map<String, String> = emptyMap()
)

/**
 * Single history item — previous step record.
 */
data class HistoryItem(
    val step: Int,
    val action: String,
    val description: String,
    val result: String            // What happened after action
)

/**
 * Task submitted by user via MainActivity.
 */
data class VayuTask(
    val id: String,
    val description: String,
    val status: String = "PENDING",  // PENDING | RUNNING | DONE | FAILED | ABORTED
    val steps: Int = 0,
    val maxSteps: Int = 50,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0L,
    val result: String = "",
    val error: String = ""
)

/**
 * Task result reported back to brain.py.
 */
data class TaskResult(
    val taskId: String,
    val status: String,
    val stepsCompleted: Int,
    val result: String,
    val error: String = "",
    val learnedPatterns: Map<String, String> = emptyMap()
)

/**
 * Brain status response.
 */
data class BrainStatus(
    val status: String,           // online | offline
    val model: String = "",
    val memoryEntries: Int = 0,
    val uptime: Long = 0L,
    val tasksCompleted: Int = 0
)

/**
 * UI Node — represents a single element in the accessibility tree.
 */
data class UINode(
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val bounds: String = "",      // [x1,y1][x2,y2]
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val focusable: Boolean = false,
    val checked: Boolean? = null,
    val resourceId: String = "",
    val packageName: String = "",
    val depth: Int = 0
)

/**
 * Recent task item for display in MainActivity.
 */
data class RecentTaskDisplay(
    val description: String,
    val status: String,
    val steps: Int,
    val duration: String
)
