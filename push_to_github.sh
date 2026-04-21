#!/bin/bash
#
# VAYU — GitHub Push Script
# =========================
# Run this to create the GitHub repo and push the project.
#
# Prerequisites:
#   1. Install GitHub CLI: https://cli.github.com/
#   2. Authenticate: gh auth login
#   3. Run this script from the VAYU directory
#
# Usage:
#   cd VAYU
#   chmod +x push_to_github.sh
#   ./push_to_github.sh [repo-name]
#

set -e

REPO_NAME="${1:-VAYU}"
DESCRIPTION="VAYU — Mobile Jarvis. Fully autonomous Android AI agent. Vision + Touch. No root."

echo "═══════════════════════════════════════"
echo "  VAYU → GitHub Push"
echo "═══════════════════════════════════════"
echo ""

# Check if gh is installed
if ! command -v gh &> /dev/null; then
    echo "❌ GitHub CLI (gh) not found."
    echo ""
    echo "Install it first:"
    echo "  https://cli.github.com/"
    echo ""
    echo "Then authenticate:"
    echo "  gh auth login"
    echo ""
    echo "Alternative: Create the repo manually and push:"
    echo "  git remote add origin https://github.com/YOUR_USERNAME/VAYU.git"
    echo "  git push -u origin main"
    exit 1
fi

# Check if authenticated
if ! gh auth status &> /dev/null; then
    echo "❌ Not authenticated with GitHub."
    echo "Run: gh auth login"
    exit 1
fi

# Create the repo
echo "Creating repository: $REPO_NAME"
gh repo create "$REPO_NAME" \
    --public \
    --description "$DESCRIPTION" \
    --source=. \
    --push

echo ""
echo "═══════════════════════════════════════"
echo "  ✅ VAYU pushed to GitHub!"
echo "═══════════════════════════════════════"
echo ""
echo "  View at: https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo ""
