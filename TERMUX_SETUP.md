# VAYU — Termux Setup Guide (Step by Step)

## Complete guide to run VAYU Brain on your Android phone

---

## PREREQUISITES

- Android phone (API 30+ / Android 11+)
- **Termux** from F-Droid (NOT Play Store — Play Store version is broken)
- Gemini API key (free from https://aistudio.google.com/app/apikey)
- VAYU APK (download from GitHub Actions after build)

---

## STEP 1: Install Termux

```
Option A — F-Droid (RECOMMENDED):
  1. Install F-Droid from https://f-droid.org
  2. Search "Termux" in F-Droid
  3. Install Termux

Option B — Direct APK:
  1. Download from https://github.com/termux/termux-app/releases
  2. Install the arm64-v8a APK
```

> ⚠️ DO NOT use the Play Store version. It's outdated and broken.

---

## STEP 2: Open Termux and Clone VAYU

Copy-paste these commands ONE BY ONE into Termux:

```bash
# Update Termux
pkg update -y && pkg upgrade -y

# Install git
pkg install git -y

# Clone VAYU repository
git clone https://github.com/rinkusharma79346-droid/Perfez-Verse.git ~/vayu

# Navigate to brain folder
cd ~/vayu/brain
```

---

## STEP 3: Run the Auto-Setup Script

This single command installs EVERYTHING:

```bash
bash setup_termux.sh
```

The script will:
- ✅ Install Python, pip, Flask, requests, Pillow
- ✅ Set up storage access
- ✅ Create VAYU data directories
- ✅ Ask for your Gemini API key
- ✅ Create run scripts (vayu-brain, vayu-status, vayu-logs, vayu-key)
- ✅ Set up auto-start on boot

When it asks for the API key, paste your key from https://aistudio.google.com/app/apikey

---

## STEP 4: Set Your Gemini API Key

If you skipped the API key during setup, or need to update it:

### Get the key:
```
1. Go to https://aistudio.google.com/app/apikey
2. Click "Create API Key"
3. Copy the key (starts with AIza...)
```

### Set it in Termux:
```bash
vayu-key
```
Then paste your key and press Enter.

### OR set manually:
```bash
export GEMINI_API_KEY="AIzaSy...your-key-here"
echo 'export GEMINI_API_KEY="AIzaSy...your-key-here"' >> ~/.bashrc
```

---

## STEP 5: Start the Brain

```bash
vayu-brain
```

You should see:
```
═══════════════════════════════════════
  VAYU Brain — Starting
═══════════════════════════════════════
  Script: /data/data/com.termux/files/home/vayu/brain/brain.py
  API Key: SET ✓
  Port: 8082
  Auto-restart: ENABLED

[HH:MM:SS] Launching brain.py...
═══════════════════════════════════════
VAYU Brain — Starting
Model: gemini-2.0-flash
API Key: SET
Memory: /data/.../brain/memory.json
Max steps per task: 50
═══════════════════════════════════════
 * Running on all addresses (0.0.0.0)
 * Running on http://127.0.0.1:8082
```

### Keep it running:
- **Don't close Termux** — the brain runs in this window
- You can press Home and use other apps — Termux keeps running
- To stop: press `Ctrl+C` in Termux

### Run in background (alternative):
```bash
# Start in background
vayu-brain &

# Or use nohup
nohup vayu-brain > ~/vayu-data/logs/brain.log 2>&1 &
```

---

## STEP 6: Verify Brain is Running

Open a NEW Termux session (swipe from left → New Session) and run:

```bash
vayu-status
```

You should see:
```
═══ VAYU Brain Status ═══
✓ Brain: ONLINE
  Model: gemini-2.0-flash
  API Key: SET
  Memory: 0 entries
  Uptime: 30s
  Tasks Done: 0
  Pending: 0
```

### Manual check:
```bash
curl http://localhost:8082/status
```

---

## STEP 7: Install VAYU APK on Your Phone

### Download the APK:

```
1. Go to: https://github.com/rinkusharma79346-droid/Perfez-Verse/actions
2. Click the latest "Build VAYU APK" workflow run
3. Scroll down to "Artifacts"
4. Download "VAYU-debug" (or "VAYU-release")
5. Unzip the downloaded file to get the .apk
```

### Install:

```
1. Open the APK file on your phone
2. Allow "Install from unknown sources" if prompted
3. Install VAYU
```

---

## STEP 8: Grant Permissions to VAYU App

### 8a. Accessibility Service (CRITICAL)

```
1. Open Settings → Accessibility
2. Find "VAYU" in the list
3. Toggle it ON
4. Accept the permission dialog
```

### 8b. Overlay Permission (for Floating HUD)

```
1. Open Settings → Apps → VAYU
2. Tap "Display over other apps"
3. Toggle it ON
```

### 8c. Notification Permission

```
1. When VAYU asks for notification permission → Allow
   OR
2. Settings → Apps → VAYU → Notifications → Allow
```

---

## STEP 9: Run Your First Task

```
1. Open VAYU app
2. You should see:
   - Service: ONLINE (green)
   - Brain: ONLINE (green)

3. Type a task in the input field:
   "Open WhatsApp and send Hello to Mom"

4. Tap EXECUTE

5. VAYU will:
   a. Capture your screen
   b. Send it to brain.py (localhost:8082)
   c. Brain analyzes with Gemini 2.0 Flash
   d. Returns action (TAP, TYPE, SCROLL, etc.)
   e. VayuService executes the action
   f. Repeat until DONE or FAIL

6. Watch the progress in the app or on the floating HUD
```

---

## STEP 10: Use the Floating HUD

When VAYU is working and you press Home:

- A **floating glass panel** appears on top of everything
- Shows: Step counter, progress bar, current action
- **Drag** it anywhere on screen
- Tap **STOP** on the HUD to abort the task
- HUD **auto-hides** 2 seconds after task completes

---

## ALL VAYU COMMANDS (Quick Reference)

| Command | What it does |
|---|---|
| `vayu` | Interactive menu (pick from options) |
| `vayu-brain` | Start brain with auto-restart |
| `vayu-status` | Check if brain is online |
| `vayu-logs` | View recent brain logs |
| `vayu-key` | Set/update Gemini API key |

---

## TROUBLESHOOTING

### Brain won't start
```bash
# Check Python is installed
python --version

# Check dependencies
pip install flask requests Pillow

# Check API key
echo $GEMINI_API_KEY

# If empty, set it:
vayu-key
```

### "Brain offline" in VAYU app
```bash
# Verify brain is running
vayu-status

# If offline, start it:
vayu-brain

# Check the port
curl http://localhost:8082/status
```

### VAYU app says "Service OFFLINE"
```
1. Go to Settings → Accessibility
2. Enable VAYU
3. Restart the VAYU app
```

### Floating HUD not appearing
```
1. Go to Settings → Apps → VAYU
2. Enable "Display over other apps"
3. Restart VAYU app
```

### Brain crashes repeatedly
```bash
# View crash logs
vayu-logs

# Check if API key is valid
curl -s "https://generativelanguage.googleapis.com/v1beta/models?key=$GEMINI_API_KEY" | head -5

# Reset brain state
curl -X POST http://localhost:8082/reset
```

### Termux gets killed in background
```
1. Open Termux
2. Swipe down notification bar
3. Tap "Termux is running" notification
4. Tap "Acquire wakelock"
   OR
5. Run in Termux: termux-wake-lock
```

---

## AUTO-START ON BOOT (Optional)

If you want VAYU Brain to start automatically when your phone boots:

```bash
# Install Termux:Boot from F-Droid
pkg install termux-boot -y

# The setup script already created the boot script
# Verify it exists:
cat ~/.termux/boot/start-vayu-brain.sh

# After installing Termux:Boot app:
# 1. Open Termux:Boot once (to initialize)
# 2. Next time phone boots, VAYU brain starts automatically
```

---

## MEMORY SYSTEM — How VAYU Learns

VAYU has a persistent memory system stored at `~/vayu/brain/memory.json`:

```bash
# View all memory
vayu-status  # shows memory entries count

# View raw memory
cat ~/vayu/brain/memory.json | python3 -m json.tool

# Or via API
curl http://localhost:8082/memory
```

The brain learns:
- **App patterns**: How to navigate specific apps (after first success)
- **UI patterns**: Where common buttons are
- **Error patterns**: What went wrong and how to recover
- **Shortcuts**: Quicker paths for repeated tasks
- **Stats**: Success rate, average steps, total tasks

After ~10 tasks, VAYU becomes noticeably faster because it remembers previous paths.

---

## KILL SWITCH — Emergency Stop

3 ways to stop VAYU immediately:

1. **In the app**: Red button (top-right corner)
2. **On the HUD**: STOP button on the floating overlay
3. **In Termux**: Press `Ctrl+C` to kill the brain

All active tasks abort instantly.

---

## SECURITY NOTES

- Brain runs on **localhost only** — not exposed to internet
- Screenshots are sent to **Gemini API** (Google's servers) for analysis
- No data stored on any third-party server besides Google's API
- Memory file is local on your device
- Kill switch always available

---

## FILE LOCATIONS

| File | Path |
|---|---|
| Brain script | `~/vayu/brain/brain.py` |
| Memory store | `~/vayu/brain/memory.json` |
| Task queue | `~/vayu/brain/tasks.json` |
| Brain logs | `~/vayu/brain/vayu.log` |
| Data directory | `~/vayu-data/` |
| Boot script | `~/.termux/boot/start-vayu-brain.sh` |
| Run commands | `$PREFIX/bin/vayu*` |
