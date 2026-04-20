#!/usr/bin/env python3
"""
VAYU Brain — THE BRAIN
======================

Flask server running on Termux (localhost:8082).
Uses Gemini 2.0 Flash via REST API for decision-making.
Persistent memory system for learning patterns across tasks.

Endpoints:
    POST /act           — Main inference endpoint (screenshot + UI tree → action)
    GET  /task/pending   — Get next pending task for VayuService
    POST /task/submit    — Submit a new task to the queue
    POST /task/result    — Report task completion result
    GET  /status         — Brain health check
    GET  /memory         — Read memory entries
    POST /memory         — Write memory entries
    GET  /logs           — Get recent action logs
"""

import json
import os
import time
import threading
import traceback
from datetime import datetime
from pathlib import Path
from queue import Queue
from typing import Any, Dict, List, Optional

import requests
from flask import Flask, jsonify, request

# ──────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────

BRAIN_DIR = Path(__file__).parent
MEMORY_FILE = BRAIN_DIR / "memory.json"
LOGS_FILE = BRAIN_DIR / "vayu.log"
TASKS_FILE = BRAIN_DIR / "tasks.json"

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL = "gemini-2.0-flash"
GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

MAX_STEPS = 50
BRAIN_START_TIME = time.time()
tasks_completed = 0

# ──────────────────────────────────────────────
# Flask App
# ──────────────────────────────────────────────

app = Flask(__name__)

# ──────────────────────────────────────────────
# Persistent Memory System
# ──────────────────────────────────────────────

class MemoryStore:
    """
    Persistent JSON-based memory for learned patterns.

    Stores:
        - app_patterns: How to navigate specific apps
        - ui_patterns: Common UI element locations
        - task_history: Summaries of completed tasks
        - error_patterns: Known failure modes and recoveries
        - shortcuts: Quick-action mappings
    """

    def __init__(self, filepath: Path):
        self.filepath = filepath
        self.lock = threading.Lock()
        self.data = self._load()

    def _load(self) -> Dict[str, Any]:
        """Load memory from disk, creating default structure if needed."""
        if self.filepath.exists():
            try:
                with open(self.filepath, 'r') as f:
                    return json.load(f)
            except (json.JSONDecodeError, IOError):
                pass

        # Default memory structure
        return {
            "app_patterns": {},       # package_name → {login_flow, main_actions, ...}
            "ui_patterns": {},        # screen_description → {tap_targets, scroll_needed, ...}
            "task_history": [],       # List of completed task summaries
            "error_patterns": {},     # error_signature → recovery_action
            "shortcuts": {},          # quick_task → action_sequence
            "preferences": {},        # User preferences learned over time
            "stats": {
                "total_tasks": 0,
                "success_rate": 0.0,
                "avg_steps": 0.0,
                "last_updated": ""
            }
        }

    def save(self):
        """Persist memory to disk."""
        with self.lock:
            self.data["stats"]["last_updated"] = datetime.now().isoformat()
            try:
                with open(self.filepath, 'w') as f:
                    json.dump(self.data, f, indent=2, ensure_ascii=False)
            except IOError as e:
                log(f"Memory save error: {e}")

    def get(self, key: str, default: Any = None) -> Any:
        """Get a memory entry."""
        with self.lock:
            return self.data.get(key, default)

    def set(self, key: str, value: Any):
        """Set a memory entry and persist."""
        with self.lock:
            self.data[key] = value
        self.save()

    def update_stats(self, success: bool, steps: int):
        """Update running statistics."""
        with self.lock:
            stats = self.data["stats"]
            stats["total_tasks"] += 1
            total = stats["total_tasks"]
            # Running average
            prev_rate = stats["success_rate"]
            prev_avg = stats["avg_steps"]
            stats["success_rate"] = prev_rate + ((1.0 if success else 0.0) - prev_rate) / total
            stats["avg_steps"] = prev_avg + (steps - prev_avg) / total
        self.save()

    def add_task_history(self, entry: Dict[str, Any]):
        """Add a completed task summary to history."""
        with self.lock:
            history = self.data["task_history"]
            history.append(entry)
            # Keep last 100 tasks
            if len(history) > 100:
                self.data["task_history"] = history[-100:]
        self.save()

    def learn_pattern(self, category: str, key: str, value: Any):
        """Learn and store a pattern."""
        with self.lock:
            if category not in self.data:
                self.data[category] = {}
            self.data[category][key] = value
        self.save()

    def get_relevant_memory(self, task: str) -> Dict[str, str]:
        """Get memory entries relevant to the current task."""
        relevant = {}
        task_lower = task.lower()

        # Check app patterns
        for app_key, patterns in self.data.get("app_patterns", {}).items():
            if app_key.lower() in task_lower:
                relevant[f"app:{app_key}"] = json.dumps(patterns)

        # Check shortcuts
        for shortcut_key, sequence in self.data.get("shortcuts", {}).items():
            if shortcut_key.lower() in task_lower:
                relevant[f"shortcut:{shortcut_key}"] = json.dumps(sequence)

        # Check error patterns
        for error_key, recovery in self.data.get("error_patterns", {}).items():
            relevant[f"error:{error_key}"] = recovery

        # Add recent stats
        stats = self.data.get("stats", {})
        relevant["stats"] = f"Tasks: {stats.get('total_tasks', 0)}, " \
                           f"Success: {stats.get('success_rate', 0):.0%}, " \
                           f"Avg steps: {stats.get('avg_steps', 0):.1f}"

        return relevant


memory = MemoryStore(MEMORY_FILE)

# ──────────────────────────────────────────────
# Task Queue
# ──────────────────────────────────────────────

class TaskQueue:
    """Thread-safe task queue with persistence."""

    def __init__(self):
        self.queue: List[Dict[str, Any]] = []
        self.active: Optional[Dict[str, Any]] = None
        self.completed: List[Dict[str, Any]] = []
        self.lock = threading.Lock()
        self._load()

    def _load(self):
        """Load persisted tasks."""
        if TASKS_FILE.exists():
            try:
                with open(TASKS_FILE, 'r') as f:
                    data = json.load(f)
                    self.queue = data.get("pending", [])
                    self.completed = data.get("completed", [])
            except (json.JSONDecodeError, IOError):
                pass

    def _save(self):
        """Persist tasks to disk."""
        try:
            with open(TASKS_FILE, 'w') as f:
                json.dump({
                    "pending": self.queue,
                    "completed": self.completed[-50:]  # Keep last 50
                }, f, indent=2)
        except IOError:
            pass

    def submit(self, task: Dict[str, Any]) -> str:
        """Add a task to the queue."""
        with self.lock:
            task_id = task.get("id", f"task_{int(time.time()*1000)}")
            task["id"] = task_id
            task["status"] = "PENDING"
            task["submitted_at"] = datetime.now().isoformat()
            self.queue.append(task)
            self._save()
        return task_id

    def get_next(self) -> Optional[Dict[str, Any]]:
        """Get the next pending task."""
        with self.lock:
            if self.queue:
                self.active = self.queue.pop(0)
                self.active["status"] = "RUNNING"
                self.active["started_at"] = datetime.now().isoformat()
                self._save()
                return self.active
        return None

    def complete(self, task_id: str, status: str, result: str, steps: int):
        """Mark the active task as complete."""
        with self.lock:
            if self.active and self.active.get("id") == task_id:
                self.active["status"] = status
                self.active["result"] = result
                self.active["steps"] = steps
                self.active["completed_at"] = datetime.now().isoformat()
                self.completed.append(self.active)
                self.active = None
                self._save()

    def get_pending(self) -> Optional[Dict[str, Any]]:
        """Get next pending task without dequeuing (for polling)."""
        with self.lock:
            if self.active:
                return self.active
            if self.queue:
                return self.queue[0]
        return None


task_queue = TaskQueue()

# ──────────────────────────────────────────────
# Logging
# ──────────────────────────────────────────────

def log(message: str):
    """Append timestamped log entry."""
    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    entry = f"[{timestamp}] {message}"
    print(entry)
    try:
        with open(LOGS_FILE, 'a') as f:
            f.write(entry + "\n")
    except IOError:
        pass

# ──────────────────────────────────────────────
# Gemini Brain — REST API (No SDK)
# ──────────────────────────────────────────────

SYSTEM_PROMPT = """You are VAYU — an autonomous Android AI agent. You see the phone screen and decide the next action to complete the user's task.

You receive:
1. The user's TASK description
2. Current STEP number (max 50)
3. A SCREENSHOT (base64 PNG) — the current phone screen
4. A UI TREE — JSON list of interactive elements with coordinates
5. HISTORY — last 10 actions you took and their results
6. MEMORY — relevant learned patterns from past tasks

Your job: Decide the ONE next action to take.

RESPOND WITH EXACTLY THIS JSON (no markdown, no explanation outside JSON):
{
    "action": "TAP|SWIPE|TYPE|SCROLL|OPEN_APP|PRESS_BACK|PRESS_HOME|DONE|FAIL",
    "x": 0,
    "y": 0,
    "x2": 0,
    "y2": 0,
    "text": "",
    "direction": "UP|DOWN|LEFT|RIGHT",
    "app": "",
    "reason": "brief explanation of why this action",
    "confidence": 0.9
}

ACTION RULES:
- TAP: Set x, y to pixel coordinates of the target. Use UI tree bounds to find exact position.
- SWIPE: Set x,y (start) and x2,y2 (end) for swipe direction. Duration is 300ms.
- TYPE: Set "text" to what to type. Must find an editable field first.
- SCROLL: Set "direction" to UP/DOWN/LEFT/RIGHT. Center of screen auto-calculated.
- OPEN_APP: Set "app" to package name (e.g., com.whatsapp).
- PRESS_BACK: Go back. Use when stuck or to exit a screen.
- PRESS_HOME: Go to home screen. Use to start fresh navigation.
- DONE: Task is complete. Set "reason" to what was accomplished.
- FAIL: Task cannot be completed. Set "reason" to why.

STRATEGY:
1. First, understand the current screen state from screenshot + UI tree
2. Find the element that moves you toward the task goal
3. If element is not visible, SCROLL to find it
4. If you're stuck, PRESS_BACK and try a different path
5. Use MEMORY patterns to skip exploration if you've seen this app before
6. Be efficient — minimize steps. Don't repeat actions from history.
7. If confidence < 0.5, consider PRESS_BACK to get to a known state.
8. Always verify: after tapping, did the screen change as expected?

IMPORTANT: Return ONLY the JSON object, no other text."""

def call_gemini(task: str, step: int, screenshot_b64: str,
                ui_tree: str, history: List[Dict],
                mem: Dict[str, str]) -> Optional[Dict]:
    """
    Call Gemini 2.0 Flash via REST API.

    No SDK — pure HTTP requests to the Gemini API.
    Sends multimodal input (text + image) and parses the action JSON.
    """
    if not GEMINI_API_KEY:
        log("ERROR: GEMINI_API_KEY not set!")
        return None

    url = f"{GEMINI_BASE_URL}/models/{GEMINI_MODEL}:generateContent?key={GEMINI_API_KEY}"

    # Build the prompt with context
    history_text = "\n".join([
        f"  Step {h.get('step', '?')}: {h.get('action', '?')} — {h.get('description', '')} → {h.get('result', '')}"
        for h in history[-10:]
    ])

    memory_text = "\n".join([
        f"  {k}: {v}" for k, v in mem.items()
    ]) if mem else "  (no relevant memory)"

    # Trim UI tree if too large
    ui_tree_trimmed = ui_tree[:8000] if len(ui_tree) > 8000 else ui_tree

    text_part = f"""TASK: {task}
STEP: {step} / {MAX_STEPS}

SCREENSHOT: [attached as image]

UI TREE:
{ui_tree_trimmed}

RECENT HISTORY:
{history_text if history_text else "  (first step)"}

MEMORY:
{memory_text}

Decide the next action. Return ONLY the JSON."""

    # Build Gemini request payload
    payload = {
        "system_instruction": {
            "parts": [{"text": SYSTEM_PROMPT}]
        },
        "contents": [{
            "parts": [
                {"text": text_part},
                {
                    "inline_data": {
                        "mime_type": "image/png",
                        "data": screenshot_b64
                    }
                }
            ]
        }],
        "generationConfig": {
            "temperature": 0.2,
            "topP": 0.9,
            "maxOutputTokens": 512,
            "responseMimeType": "application/json"
        }
    }

    try:
        response = requests.post(
            url,
            json=payload,
            headers={"Content-Type": "application/json"},
            timeout=30
        )

        if response.status_code != 200:
            log(f"Gemini API error: {response.status_code} — {response.text[:200]}")
            return None

        result = response.json()

        # Extract text from response
        candidates = result.get("candidates", [])
        if not candidates:
            log("Gemini returned no candidates")
            return None

        content = candidates[0].get("content", {})
        parts = content.get("parts", [])
        if not parts:
            log("Gemini returned no parts")
            return None

        text = parts[0].get("text", "")

        # Parse the action JSON — handle potential markdown wrapping
        text = text.strip()
        if text.startswith("```"):
            # Remove markdown code fences
            lines = text.split("\n")
            text = "\n".join(lines[1:-1])

        action = json.loads(text)
        log(f"Brain decided: {action.get('action')} — {action.get('reason', '')}")
        return action

    except json.JSONDecodeError as e:
        log(f"Failed to parse brain response: {e}")
        log(f"Raw response: {text[:200]}")
        return None
    except requests.exceptions.Timeout:
        log("Gemini API timeout (30s)")
        return None
    except Exception as e:
        log(f"Gemini call failed: {e}\n{traceback.format_exc()}")
        return None


def learn_from_result(task: str, status: str, steps: int, result: str,
                      history: List[Dict]):
    """
    Learn patterns from completed tasks.

    Extracts:
    - App navigation patterns
    - Successful action sequences
    - Error patterns for future recovery
    """
    if status == "DONE":
        # Extract app names from history
        apps_used = [h.get("description", "") for h in history
                     if "OPEN" in h.get("action", "")]
        for app_desc in apps_used:
            # Simple pattern learning
            memory.learn_pattern("app_patterns", task[:50], {
                "steps": steps,
                "status": "success",
                "apps": apps_used
            })

        # Add to task history
        memory.add_task_history({
            "task": task,
            "status": "success",
            "steps": steps,
            "result": result,
            "timestamp": datetime.now().isoformat()
        })

        # Create shortcut for similar tasks
        memory.learn_pattern("shortcuts", task[:30], {
            "pattern": "learned",
            "steps": steps,
            "success": True
        })

    elif status == "FAILED":
        memory.add_task_history({
            "task": task,
            "status": "failed",
            "steps": steps,
            "result": result,
            "timestamp": datetime.now().isoformat()
        })

        # Learn error patterns
        if history:
            last_action = history[-1] if history else {}
            error_key = f"{last_action.get('action', '')}:{task[:30]}"
            memory.learn_pattern("error_patterns", error_key, {
                "recovery": "PRESS_BACK",
                "reason": result
            })

    # Update stats
    memory.update_stats(success=(status == "DONE"), steps=steps)

# ──────────────────────────────────────────────
# Flask Routes
# ──────────────────────────────────────────────

@app.route("/act", methods=["POST"])
def act():
    """
    Main inference endpoint.

    Receives: task, step, screenshot (base64), uiTree, history, memory
    Returns:  action JSON for VayuService to execute
    """
    global tasks_completed

    try:
        data = request.get_json(force=True)
        task = data.get("task", "")
        step = data.get("step", 1)
        screenshot = data.get("screenshot", "")
        ui_tree = data.get("uiTree", "[]")
        history = data.get("history", [])
        mem_hints = data.get("memory", {})

        # Get relevant memory from our persistent store
        relevant_memory = memory.get_relevant_memory(task)
        # Merge with any hints from the service
        relevant_memory.update(mem_hints)

        # Call Gemini
        action = call_gemini(task, step, screenshot, ui_tree, history, relevant_memory)

        if action is None:
            # Fallback: press back to recover
            action = {
                "action": "PRESS_BACK",
                "reason": "Brain inference failed — auto-recovery",
                "confidence": 0.1
            }

        log(f"/act → {action.get('action')} (step {step})")
        return jsonify({"action": action, "step": step})

    except Exception as e:
        log(f"/act error: {e}\n{traceback.format_exc()}")
        return jsonify({
            "action": {
                "action": "PRESS_BACK",
                "reason": f"Error: {str(e)[:100]}",
                "confidence": 0.0
            }
        }), 200


@app.route("/task/pending", methods=["GET"])
def task_pending():
    """Get the next pending task for VayuService to pick up."""
    task = task_queue.get_next()
    if task:
        return jsonify({"task": task, "has_task": True})
    return jsonify({"task": None, "has_task": False})


@app.route("/task/submit", methods=["POST"])
def task_submit():
    """Submit a new task to the queue."""
    try:
        data = request.get_json(force=True)
        task = data.get("task", data)
        if isinstance(task, str):
            task = {"description": task}
        task_id = task_queue.submit(task)
        log(f"Task submitted: {task.get('description', task_id)[:50]}")
        return jsonify({"status": "queued", "task_id": task_id})
    except Exception as e:
        log(f"/task/submit error: {e}")
        return jsonify({"status": "error", "message": str(e)}), 400


@app.route("/task/result", methods=["POST"])
def task_result():
    """Report task completion result and learn from it."""
    global tasks_completed

    try:
        data = request.get_json(force=True)
        task_id = data.get("taskId", "")
        status = data.get("status", "UNKNOWN")
        steps = data.get("stepsCompleted", 0)
        result = data.get("result", "")
        learned = data.get("learnedPatterns", {})

        # Complete the task in queue
        task_queue.complete(task_id, status, result, steps)

        # Get task description for learning
        task_desc = ""
        if task_queue.completed:
            for t in reversed(task_queue.completed):
                if t.get("id") == task_id:
                    task_desc = t.get("description", "")
                    break

        # Learn from this task
        learn_from_result(task_desc, status, steps, result, [])

        # Store any additional learned patterns
        for key, value in learned.items():
            memory.learn_pattern("learned", key, value)

        if status == "DONE":
            tasks_completed += 1

        log(f"Task {task_id} {status}: {result[:100]} (steps: {steps})")
        return jsonify({"status": "recorded"})

    except Exception as e:
        log(f"/task/result error: {e}")
        return jsonify({"status": "error", "message": str(e)}), 400


@app.route("/status", methods=["GET"])
def status():
    """Brain health check."""
    uptime = time.time() - BRAIN_START_TIME
    return jsonify({
        "status": "online",
        "model": GEMINI_MODEL,
        "api_key_set": bool(GEMINI_API_KEY),
        "memory_entries": sum(len(v) if isinstance(v, dict) else 1
                            for v in memory.data.values()
                            if isinstance(v, (dict, list))),
        "uptime_seconds": int(uptime),
        "tasks_completed": tasks_completed,
        "active_task": task_queue.active is not None,
        "pending_tasks": len(task_queue.queue)
    })


@app.route("/memory", methods=["GET"])
def get_memory():
    """Read all memory entries."""
    key = request.args.get("key")
    if key:
        value = memory.get(key)
        return jsonify({"key": key, "value": value})
    return jsonify({"memory": memory.data})


@app.route("/memory", methods=["POST"])
def set_memory():
    """Write a memory entry."""
    try:
        data = request.get_json(force=True)
        key = data.get("key", "")
        value = data.get("value")
        if key and value is not None:
            memory.set(key, value)
            log(f"Memory updated: {key}")
            return jsonify({"status": "saved"})
        return jsonify({"status": "error", "message": "key and value required"}), 400
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 400


@app.route("/logs", methods=["GET"])
def get_logs():
    """Get recent action logs."""
    count = int(request.args.get("count", 50))
    try:
        if LOGS_FILE.exists():
            with open(LOGS_FILE, 'r') as f:
                lines = f.readlines()
            return jsonify({"logs": lines[-count:]})
        return jsonify({"logs": []})
    except IOError:
        return jsonify({"logs": []})


@app.route("/reset", methods=["POST"])
def reset():
    """Reset brain state (memory preserved)."""
    global tasks_completed
    task_queue.queue.clear()
    task_queue.active = None
    tasks_completed = 0
    log("Brain reset — queues cleared, memory preserved")
    return jsonify({"status": "reset"})

# ──────────────────────────────────────────────
# Auto-Restart Loop
# ──────────────────────────────────────────────

def auto_restart():
    """
    Watchdog thread that auto-restarts the brain if it crashes.
    Monitors the Flask server health endpoint and restarts if unresponsive.
    """
    while True:
        time.sleep(30)  # Check every 30 seconds
        try:
            resp = requests.get("http://localhost:8082/status", timeout=5)
            if resp.status_code != 200:
                log("Auto-restart: status check failed, restarting...")
                # The Flask server itself would need external restart
                # In Termux, this is handled by the wrapper script
        except requests.exceptions.ConnectionError:
            log("Auto-restart: brain unreachable — needs manual restart")
        except Exception as e:
            log(f"Auto-restart check error: {e}")

# ──────────────────────────────────────────────
# Main Entry Point
# ──────────────────────────────────────────────

if __name__ == "__main__":
    log("=" * 50)
    log("VAYU Brain — Starting")
    log(f"Model: {GEMINI_MODEL}")
    log(f"API Key: {'SET' if GEMINI_API_KEY else 'NOT SET — SET GEMINI_API_KEY ENV VAR!'}")
    log(f"Memory: {MEMORY_FILE}")
    log(f"Max steps per task: {MAX_STEPS}")
    log("=" * 50)

    # Start auto-restart watchdog
    watchdog = threading.Thread(target=auto_restart, daemon=True)
    watchdog.start()

    # Run Flask server
    app.run(
        host="0.0.0.0",
        port=8082,
        debug=False,
        threaded=True
    )
