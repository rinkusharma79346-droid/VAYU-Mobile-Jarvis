# VAYU — Mobile Jarvis

**Fully autonomous Android AI agent.** Sees the screen, thinks, and acts — like a human but faster, tireless, overnight. No root. No PC. No APIs to apps. Pure vision + touch.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                    VAYU                         │
│                                                 │
│  ┌──────────┐   ┌──────────┐   ┌────────────┐  │
│  │ THE FACE │   │THE HANDS │   │  THE BRAIN │  │
│  │          │   │          │   │            │  │
│  │ Main     │──▶│ Vayu     │──▶│ brain.py   │  │
│  │ Activity │   │ Service  │   │ (Termux)   │  │
│  │          │   │          │   │            │  │
│  │ Glass UI │   │ Screen   │   │ Gemini 2.0 │  │
│  │ Task     │   │ Capture  │   │ Flash API  │  │
│  │ Input    │   │ UI Tree  │   │            │  │
│  │ Status   │   │ Gestures │   │ Memory     │  │
│  │ Kill     │   │ ReAct    │   │ Learn      │  │
│  │ Switch   │   │ Loop     │   │ Auto-      │  │
│  │          │   │          │   │ restart    │  │
│  └──────────┘   └──────────┘   └────────────┘  │
│       ▲                              ▲          │
│       │         ┌──────────┐        │          │
│       └─────────│Floating  │────────┘          │
│                 │HUD (PiP) │                   │
│                 │Drag+Glass│                   │
│                 └──────────┘                   │
└─────────────────────────────────────────────────┘
```

## Components

### 1. VayuService.kt — THE HANDS
Android AccessibilityService that controls the phone without root.
- **Screen capture**: `takeScreenshot()` every step (API 30+)
- **UI tree reading**: Full node tree with pixel coordinates
- **ReAct loop**: 50 steps max, 0.6s delay per step
- **Actions**: TAP, SWIPE, TYPE, SCROLL, OPEN_APP, PRESS_BACK, PRESS_HOME, DONE, FAIL
- **Smart recovery**: Same-screen detection → auto PRESS_BACK after 3 stuck steps
- **Gesture execution**: Via `GestureDescription` (no root needed)
- **Task polling**: Polls brain.py at `localhost:8082/task/pending`

### 2. brain.py — THE BRAIN
Flask server on Termux (localhost:8082), powered by Gemini 2.0 Flash.
- **REST API**: Calls Gemini directly via HTTP (no SDK dependency)
- **Persistent memory**: `memory.json` stores learned app patterns, UI patterns, error recoveries
- **Auto-restart**: Watchdog thread monitors brain health
- **Pattern learning**: Gets smarter with each completed task
- **Endpoints**:
  - `POST /act` — Main inference (screenshot + UI tree → action)
  - `GET /task/pending` — Next task for VayuService
  - `POST /task/submit` — Submit new task
  - `POST /task/result` — Report completion + trigger learning
  - `GET /status` — Brain health check
  - `GET /memory` — Read memory entries
  - `POST /memory` — Write memory entries
  - `GET /logs` — Action logs

### 3. MainActivity.kt — THE FACE
iOS 28-style Glassmorphism UI.
- **Black background** with blur glass cards
- **Cyan accent** throughout
- **Status card**: Service + Brain + Task + Steps + Timer + Current Action
- **Task input**: Glass-styled with "EXECUTE" button
- **Recent tasks**: Scrollable list with status
- **Kill switch**: Always-visible red button (top-right)

### 4. FloatingHUD — PiP OVERLAY
Picture-in-Picture floating overlay for when user exits the app.
- **Draggable** anywhere on screen
- **Blurred glass** background
- **Cyan progress bar** with step counter
- **Live action description**
- **STOP button** (kill switch)
- **Auto-hides** when task completes (2s delay)

---

## Setup

### Step 1: Termux (The Brain)

```bash
# Install Termux from F-Droid (not Play Store)
# Open Termux and run:

pkg update -y && pkg install git -y
git clone https://github.com/rinkusharma79346-droid/VAYU-Mobile-Jarvis.git ~/vayu
cd ~/vayu/brain
bash setup_termux.sh

# Set your Gemini API key
export GEMINI_API_KEY='your-key-from-https://aistudio.google.com/app/apikey'

# Start the brain (with auto-restart)
vayu-brain
```

### Step 2: Android App (The Hands + Face)

1. Open project in Android Studio
2. Build & install on device (API 30+)
3. Enable VAYU in **Settings → Accessibility**
4. Grant overlay permission (**Settings → Apps → VAYU → Display over other apps**)
5. Start brain.py in Termux
6. Open VAYU app → type task → EXECUTE

### Step 3: Profit

VAYU will now:
1. Capture your screen
2. Send it to brain.py
3. Brain analyzes with Gemini 2.0 Flash
4. Returns an action (TAP, TYPE, SCROLL, etc.)
5. VayuService executes the action
6. Repeat until task is DONE or FAIL
7. Learn from the result for next time

---

## Permissions Required

| Permission | Why |
|---|---|
| `ACCESSIBILITY_SERVICE` | Read screen, perform gestures |
| `SYSTEM_ALERT_WINDOW` | Floating HUD overlay |
| `FOREGROUND_SERVICE` | Keep service alive while working |
| `INTERNET` | Communicate with brain.py |
| `POST_NOTIFICATIONS` | Status notifications |
| `WRITE_EXTERNAL_STORAGE` | Save screenshots temporarily |

---

## Jarvis-Level Features

| Feature | Basic Agent | VAYU Jarvis |
|---|---|---|
| Speed | 1.2s/step | 0.6s/step |
| Memory | None | Persistent JSON |
| Recovery | None | Auto-retry + PRESS_BACK |
| UI | Basic dark | iOS28 glass blur |
| Monitoring | None | Live PiP HUD |
| Kill switch | None | Always-on red button |
| Task queue | Single | Multi-task queue |
| Logging | None | Full timestamped logs |
| Brain uptime | Manual | Auto-restart loop |
| Learned patterns | None | Gets smarter per task |

---

## Project Structure

```
VAYU/
├── app/
│   ├── src/main/
│   │   ├── java/com/vayu/agent/
│   │   │   ├── VayuService.kt      # THE HANDS — Accessibility Service
│   │   │   ├── MainActivity.kt     # THE FACE — Glass UI
│   │   │   ├── HUDService.kt       # Floating HUD service
│   │   │   ├── VayuApp.kt          # Application init
│   │   │   └── models/
│   │   │       └── ActionModels.kt # Data classes
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml
│   │   │   │   └── floating_hud.xml
│   │   │   ├── values/
│   │   │   │   ├── colors.xml
│   │   │   │   ├── strings.xml
│   │   │   │   ├── themes.xml
│   │   │   │   └── dimens.xml
│   │   │   ├── drawable/
│   │   │   │   ├── bg_glass_card.xml
│   │   │   │   ├── bg_glass_input.xml
│   │   │   │   ├── bg_kill_switch.xml
│   │   │   │   ├── bg_hud.xml
│   │   │   │   ├── bg_cyan_button.xml
│   │   │   │   └── bg_progress_bar.xml
│   │   │   └── xml/
│   │   │       └── vayu_accessibility_config.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── brain/
│   ├── brain.py           # THE BRAIN — Flask + Gemini
│   ├── memory.json        # Persistent learned patterns
│   ├── requirements.txt   # Python dependencies
│   └── setup_termux.sh    # One-time Termux setup
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## API Key

Get a Gemini API key from [Google AI Studio](https://aistudio.google.com/app/apikey).

Set it before running brain.py:
```bash
export GEMINI_API_KEY='AIza...'
```

Or add to `~/.bashrc` for persistence:
```bash
echo 'export GEMINI_API_KEY="your-key"' >> ~/.bashrc
source ~/.bashrc
```

---

## Safety

- **Kill switch**: Red button always visible in-app and on floating HUD
- **Max steps**: Hard limit of 50 actions per task
- **Stuck detection**: Auto PRESS_BACK after 3 identical screens
- **No root**: All actions via Accessibility Service (official Android API)
- **Local brain**: Brain runs on-device in Termux, screenshots never leave localhost (only sent to Gemini API)

---

## License

MIT
