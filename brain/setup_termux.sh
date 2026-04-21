#!/data/data/com.termux/files/usr/bin/bash
# ════════════════════════════════════════════════════════════════
#  VAYU Brain — Complete Termux Setup
#  Full step-by-step installer with auto-configuration
# ════════════════════════════════════════════════════════════════
#
#  Run this ONCE in Termux on your Android phone.
#  It installs everything: Python, Flask, dependencies,
#  creates the brain runner, and sets up auto-start.
#
#  Usage:
#    pkg update && pkg install git -y
#    git clone https://github.com/rinkusharma79346-droid/VAYU-Mobile-Jarvis.git vayu
#    cd vayu/brain
#    bash setup_termux.sh
#
# ════════════════════════════════════════════════════════════════

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  ${BOLD}VAYU Brain — Termux Full Setup${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 1: Update Termux packages
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 1/8]${NC} ${BOLD}Updating Termux packages...${NC}"
pkg update -y 2>/dev/null || apt update -y
pkg upgrade -y 2>/dev/null || apt upgrade -y
echo -e "  ${GREEN}✓ Packages updated${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 2: Install core packages
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 2/8]${NC} ${BOLD}Installing core packages...${NC}"
pkg install -y python python-pip git termux-api termux-exec openssl
echo -e "  ${GREEN}✓ Python, Git, Termux API installed${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 3: Setup storage access
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 3/8]${NC} ${BOLD}Setting up storage access...${NC}"
termux-setup-storage 2>/dev/null && echo -e "  ${GREEN}✓ Storage access granted${NC}" || echo -e "  ${YELLOW}⚠ Storage access denied (non-critical)${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 4: Upgrade pip and install wheel
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 4/8]${NC} ${BOLD}Upgrading pip...${NC}"
pip install --upgrade pip setuptools wheel
echo -e "  ${GREEN}✓ pip upgraded${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 5: Install Python dependencies
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 5/8]${NC} ${BOLD}Installing Python packages...${NC}"
pip install flask>=3.0.0
pip install requests>=2.31.0
pip install Pillow>=10.0.0
echo -e "  ${GREEN}✓ Flask, requests, Pillow installed${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 6: Create VAYU data directories
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 6/8]${NC} ${BOLD}Creating VAYU directories...${NC}"
mkdir -p ~/vayu-data
mkdir -p ~/vayu-data/logs
mkdir -p ~/vayu-data/memory
mkdir -p ~/vayu-data/backups
echo -e "  ${GREEN}✓ Data directories created${NC}"
echo ""

# ──────────────────────────────────────────
# STEP 7: Configure API key
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 7/8]${NC} ${BOLD}Gemini API Key Setup${NC}"
echo ""
echo "  ┌─────────────────────────────────────────────────────┐"
echo "  │  You need a Google Gemini API key.                  │"
echo "  │                                                     │"
echo "  │  Get one FREE at:                                   │"
echo "  │  https://aistudio.google.com/app/apikey             │"
echo "  │                                                     │"
echo "  │  Click 'Create API Key' → Copy the key             │"
echo "  └─────────────────────────────────────────────────────┘"
echo ""
read -p "  Paste your Gemini API key (or press Enter to skip): " API_KEY

if [ -n "$API_KEY" ]; then
    # Write to .bashrc for persistence
    grep -q "GEMINI_API_KEY" ~/.bashrc 2>/dev/null && sed -i '/GEMINI_API_KEY/d' ~/.bashrc
    echo "export GEMINI_API_KEY=\"$API_KEY\"" >> ~/.bashrc
    export GEMINI_API_KEY="$API_KEY"
    echo -e "  ${GREEN}✓ API key saved to ~/.bashrc${NC}"
else
    echo -e "  ${YELLOW}⚠ Skipped — set it later with:${NC}"
    echo "  export GEMINI_API_KEY='your-key-here'"
    echo "  echo 'export GEMINI_API_KEY=\"your-key\"' >> ~/.bashrc"
fi
echo ""

# ──────────────────────────────────────────
# STEP 8: Create run scripts & boot service
# ──────────────────────────────────────────
echo -e "${YELLOW}[STEP 8/8]${NC} ${BOLD}Creating run scripts...${NC}"

# Main brain runner with auto-restart
cat > $PREFIX/bin/vayu-brain << 'BRAINRUN'
#!/data/data/com.termux/files/usr/bin/bash
# ════════════════════════════════════════════
#  VAYU Brain Runner — Auto-restart loop
# ════════════════════════════════════════════

CYAN='\033[0;36m'
RED='\033[0;31m'
GREEN='\033[0;32m'
BOLD='\033[1m'
NC='\033[0m'

# Find brain.py location
BRAIN_SCRIPT=""
SEARCH_PATHS=(
    "$HOME/vayu/brain/brain.py"
    "$HOME/storage/shared/VAYU/brain/brain.py"
    "$HOME/Perfez-Verse/brain/brain.py"
    "$HOME/vayu/Perfez-Verse/brain/brain.py"
    "$(find $HOME -name "brain.py" -path "*/brain/*" 2>/dev/null | head -1)"
)

for path in "${SEARCH_PATHS[@]}"; do
    if [ -f "$path" ] 2>/dev/null; then
        BRAIN_SCRIPT="$path"
        break
    fi
done

if [ -z "$BRAIN_SCRIPT" ]; then
    echo -e "${RED}✗ brain.py not found!${NC}"
    echo "  Clone the repo first:"
    echo "  git clone https://github.com/rinkusharma79346-droid/VAYU-Mobile-Jarvis.git ~/vayu"
    exit 1
fi

BRAIN_DIR=$(dirname "$BRAIN_SCRIPT")
cd "$BRAIN_DIR"

echo -e "${CYAN}═══════════════════════════════════════${NC}"
echo -e "${CYAN}  ${BOLD}VAYU Brain — Starting${NC}"
echo -e "${CYAN}═══════════════════════════════════════${NC}"
echo -e "  Script: ${BRAIN_SCRIPT}"
echo -e "  API Key: ${GREEN}$([ -n \"$GEMINI_API_KEY\" ] && echo 'SET ✓' || echo 'NOT SET ✗')${NC}"
echo -e "  Port: 8082"
echo -e "  Auto-restart: ENABLED"
echo ""

CRASH_COUNT=0
MAX_CRASHES=10

while [ $CRASH_COUNT -lt $MAX_CRASHES ]; do
    echo -e "[$(date '+%H:%M:%S')] ${GREEN}Launching brain.py...${NC}"
    python "$BRAIN_SCRIPT"
    EXIT_CODE=$?

    if [ $EXIT_CODE -eq 0 ]; then
        echo -e "[$(date '+%H:%M:%S')] Clean exit."
        break
    fi

    CRASH_COUNT=$((CRASH_COUNT + 1))
    echo -e "[$(date '+%H:%M:%S')] ${RED}Crashed (exit $EXIT_CODE) — attempt $CRASH_COUNT/$MAX_CRASHES${NC}"
    echo -e "  Restarting in 3 seconds..."
    sleep 3
done

if [ $CRASH_COUNT -ge $MAX_CRASHES ]; then
    echo -e "${RED}Too many crashes. Check logs at ~/vayu-data/logs/${NC}"
fi
BRAINRUN

chmod +x $PREFIX/bin/vayu-brain

# Quick start script
cat > $PREFIX/bin/vayu << 'QUICKSTART'
#!/data/data/com.termux/files/usr/bin/bash
echo "╔═══════════════════════════════════╗"
echo "║         VAYU Quick Start         ║"
echo "╠═══════════════════════════════════╣"
echo "║  1. Start Brain   → vayu-brain   ║"
echo "║  2. Check Status  → vayu-status  ║"
echo "║  3. View Logs     → vayu-logs    ║"
echo "║  4. Set API Key   → vayu-key     ║"
echo "╚═══════════════════════════════════╝"
read -p "Choice [1-4]: " choice
case $choice in
    1) vayu-brain ;;
    2) vayu-status ;;
    3) vayu-logs ;;
    4) vayu-key ;;
    *) echo "Invalid choice" ;;
esac
QUICKSTART

chmod +x $PREFIX/bin/vayu

# Status checker
cat > $PREFIX/bin/vayu-status << 'STATUSCHECK'
#!/data/data/com.termux/files/usr/bin/bash
echo "═══ VAYU Brain Status ═══"
if curl -s http://localhost:8082/status > /dev/null 2>&1; then
    echo "✓ Brain: ONLINE"
    curl -s http://localhost:8082/status | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(f'  Model: {d.get(\"model\", \"?\")}')
    print(f'  API Key: {\"SET\" if d.get(\"api_key_set\") else \"NOT SET\"}')
    print(f'  Memory: {d.get(\"memory_entries\", 0)} entries')
    print(f'  Uptime: {d.get(\"uptime_seconds\", 0)}s')
    print(f'  Tasks Done: {d.get(\"tasks_completed\", 0)}')
    print(f'  Pending: {d.get(\"pending_tasks\", 0)}')
except: print('  (parse error)')
"
else
    echo "✗ Brain: OFFLINE"
    echo "  Start with: vayu-brain"
fi
STATUSCHECK

chmod +x $PREFIX/bin/vayu-status

# Log viewer
cat > $PREFIX/bin/vayu-logs << 'LOGVIEW'
#!/data/data/com.termux/files/usr/bin/bash
if curl -s http://localhost:8082/logs > /dev/null 2>&1; then
    curl -s http://localhost:8082/logs?count=30 | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    for line in d.get('logs', []):
        print(line.rstrip())
except: print('Parse error')
"
else
    echo "Brain offline — no logs available"
fi
LOGVIEW

chmod +x $PREFIX/bin/vayu-logs

# API key setter
cat > $PREFIX/bin/vayu-key << 'KEYSET'
#!/data/data/com.termux/files/usr/bin/bash
read -p "Enter Gemini API key: " KEY
if [ -n "$KEY" ]; then
    grep -q "GEMINI_API_KEY" ~/.bashrc 2>/dev/null && sed -i '/GEMINI_API_KEY/d' ~/.bashrc
    echo "export GEMINI_API_KEY=\"$KEY\"" >> ~/.bashrc
    export GEMINI_API_KEY="$KEY"
    echo "✓ API key saved. Restart brain for it to take effect."
else
    echo "No key entered."
fi
KEYSET

chmod +x $PREFIX/bin/vayu-key

# ── Boot service (auto-start brain on Termux open) ──
mkdir -p ~/.termux
cat > ~/.termux/boot/start-vayu-brain.sh << 'BOOTSERVICE'
#!/data/data/com.termux/files/usr/bin/bash
# Auto-start VAYU Brain when Termux:Boot triggers
sleep 5
vayu-brain &
BOOTSERVICE

chmod +x ~/.termux/boot/start-vayu-brain.sh 2>/dev/null || true

echo -e "  ${GREEN}✓ All scripts created${NC}"
echo ""

# ══════════════════════════════════════
# COMPLETE
# ══════════════════════════════════════

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  ${BOLD}✓ SETUP COMPLETE!${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  ${BOLD}Available Commands:${NC}"
echo ""
echo -e "  ${CYAN}vayu${NC}          — Interactive menu"
echo -e "  ${CYAN}vayu-brain${NC}    — Start brain (with auto-restart)"
echo -e "  ${CYAN}vayu-status${NC}   — Check if brain is running"
echo -e "  ${CYAN}vayu-logs${NC}     — View recent brain logs"
echo -e "  ${CYAN}vayu-key${NC}      — Set/update Gemini API key"
echo ""
echo -e "  ${BOLD}Quick Start:${NC}"
echo ""
echo -e "  1. Set API key:"
echo -e "     ${CYAN}vayu-key${NC}"
echo ""
echo -e "  2. Start the brain:"
echo -e "     ${CYAN}vayu-brain${NC}"
echo ""
echo -e "  3. Open VAYU app on phone → type task → EXECUTE"
echo ""
echo -e "  ${BOLD}Install VAYU App:${NC}"
echo ""
echo -e "  Download APK from GitHub Actions:"
echo -e "  ${CYAN}https://github.com/rinkusharma79346-droid/VAYU-Mobile-Jarvis/actions${NC}"
echo ""
echo -e "  After installing APK:"
echo -e "  • Enable VAYU in Settings → Accessibility"
echo -e "  • Grant overlay permission"
echo -e "  • Start brain.py in Termux"
echo -e "  • You're ready to go!"
echo ""
