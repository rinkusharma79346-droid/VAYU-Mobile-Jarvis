#!/data/data/com.termux/files/usr/bin/bash
#
# VAYU Brain — Termux Setup Script
# ================================
# Run this once in Termux to set up the brain environment.
#
# Usage:
#   chmod +x setup_termux.sh
#   ./setup_termux.sh
#

set -e

echo "═══════════════════════════════════════"
echo "  VAYU Brain — Termux Setup"
echo "═══════════════════════════════════════"

# Update packages
echo ""
echo "[1/6] Updating Termux packages..."
pkg update -y && pkg upgrade -y

# Install Python
echo ""
echo "[2/6] Installing Python..."
pkg install python -y

# Install dependencies
echo ""
echo "[3/6] Installing Python packages..."
pip install --upgrade pip
pip install -r requirements.txt

# Set up storage access
echo ""
echo "[4/6] Setting up storage access..."
termux-setup-storage 2>/dev/null || echo "  (Storage already set up or denied)"

# Create brain data directory
echo ""
echo "[5/6] Creating brain data directory..."
mkdir -p ~/vayu-data
mkdir -p ~/vayu-data/logs
mkdir -p ~/vayu-data/memory

# Set API key prompt
echo ""
echo "[6/6] Gemini API Key configuration"
echo "  ─────────────────────────────────"
echo "  You need a Gemini API key from:"
echo "  https://aistudio.google.com/app/apikey"
echo ""
echo "  Set it with:"
echo "  export GEMINI_API_KEY='your-key-here'"
echo ""
echo "  Or add to ~/.bashrc:"
echo "  echo 'export GEMINI_API_KEY=\"your-key-here\"' >> ~/.bashrc"
echo ""

# Create run script
cat > ~/vayu-run.sh << 'RUNSCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# VAYU Brain Runner with auto-restart

cd "$(dirname "$0")"
BRAIN_DIR="$(pwd)"

echo "Starting VAYU Brain with auto-restart..."

while true; do
    echo "[$(date '+%H:%M:%S')] Launching brain.py..."
    python brain.py
    EXIT_CODE=$?
    echo "[$(date '+%H:%M:%S')] Brain exited with code $EXIT_CODE"
    if [ $EXIT_CODE -eq 0 ]; then
        echo "Clean exit — not restarting"
        break
    fi
    echo "Restarting in 3 seconds..."
    sleep 3
done
RUNSCRIPT

chmod +x ~/vayu-run.sh

echo ""
echo "═══════════════════════════════════════"
echo "  Setup Complete!"
echo "═══════════════════════════════════════"
echo ""
echo "  To start the brain:"
echo "  1. Set your API key:"
echo "     export GEMINI_API_KEY='your-key'"
echo ""
echo "  2. Run the brain:"
echo "     ~/vayu-run.sh"
echo ""
echo "  3. Or run directly:"
echo "     python brain.py"
echo ""
echo "  The brain runs on localhost:8082"
echo "  VAYU Android app connects automatically."
echo ""
