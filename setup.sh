#!/usr/bin/env bash
# ================================================
#   ChildFocus - Automated Setup Script (Linux)
#   Tested on: Linux Mint 22 / Ubuntu 24.04
# ================================================

set -e
REQUIRED_PYTHON="3.13"
REQUIRED_NODE="24.14.0"
REQUIRED_FFMPEG="8.0.1"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "[INFO] $1"; }

echo ""
echo "================================================"
echo "  ChildFocus - Automated Setup Script"
echo "  Children's Content Filtering System"
echo "================================================"
echo ""

# =============================================================================
# STEP 1 - PYTHON 3.13
# =============================================================================
echo "[1/6] Checking Python ${REQUIRED_PYTHON}..."

PYTHON=""
if python3.13 --version &>/dev/null; then
    PYTHON="python3.13"
elif python3 --version 2>&1 | grep -q "3\.13"; then
    PYTHON="python3"
fi

if [ -z "$PYTHON" ]; then
    warn "Python ${REQUIRED_PYTHON} not found."
    read -rp "Install Python ${REQUIRED_PYTHON} via deadsnakes PPA now? (y/n): " CONFIRM_PY
    if [[ "$CONFIRM_PY" =~ ^[Yy]$ ]]; then
        sudo apt-get update -qq
        sudo apt-get install -y software-properties-common
        sudo add-apt-repository -y ppa:deadsnakes/ppa
        sudo apt-get update -qq
        sudo apt-get install -y python3.13 python3.13-venv python3.13-dev
        PYTHON="python3.13"
        ok "Python ${REQUIRED_PYTHON} installed."
    else
        fail "Python not installed. Setup cannot continue."
        exit 1
    fi
else
    $PYTHON --version
    ok "Python ${REQUIRED_PYTHON} confirmed."
fi

# Ensure pip is available for the chosen Python
if ! $PYTHON -m pip --version &>/dev/null; then
    info "pip not found for $PYTHON — installing..."
    curl -sS https://bootstrap.pypa.io/get-pip.py | $PYTHON
fi
echo ""

# =============================================================================
# STEP 2 - NODE.JS
# =============================================================================
echo "[2/6] Checking Node.js v${REQUIRED_NODE}..."

if node --version &>/dev/null; then
    FOUND_NODE=$(node --version)
    if echo "$FOUND_NODE" | grep -q "v${REQUIRED_NODE}"; then
        ok "Node.js v${REQUIRED_NODE} confirmed."
    else
        warn "Node.js found (${FOUND_NODE}) but v${REQUIRED_NODE} is recommended."
        echo "  [1] Install v${REQUIRED_NODE} via NVM (recommended, keeps both)"
        echo "  [2] Keep current version and continue"
        read -rp "Enter choice (1/2): " NODE_CHOICE
        if [ "$NODE_CHOICE" = "1" ]; then
            info "Installing NVM..."
            curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
            export NVM_DIR="$HOME/.nvm"
            # shellcheck source=/dev/null
            [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
            nvm install "${REQUIRED_NODE}"
            nvm use "${REQUIRED_NODE}"
            ok "Node.js v${REQUIRED_NODE} installed via NVM."
        else
            warn "Keeping current Node.js. May still work."
        fi
    fi
else
    warn "Node.js not found."
    read -rp "Install Node.js v${REQUIRED_NODE} via NVM now? (y/n): " CONFIRM_NODE
    if [[ "$CONFIRM_NODE" =~ ^[Yy]$ ]]; then
        curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
        export NVM_DIR="$HOME/.nvm"
        # shellcheck source=/dev/null
        [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
        nvm install "${REQUIRED_NODE}"
        nvm use "${REQUIRED_NODE}"
        ok "Node.js v${REQUIRED_NODE} installed."
    else
        warn "Node.js skipped. YouTube extraction will not work."
    fi
fi
echo ""

# =============================================================================
# STEP 3 - FFMPEG
# =============================================================================
echo "[3/6] Checking ffmpeg..."

if ffmpeg -version &>/dev/null; then
    FOUND_FF=$(ffmpeg -version 2>&1 | head -1)
    if echo "$FOUND_FF" | grep -q "${REQUIRED_FFMPEG}"; then
        ok "ffmpeg ${REQUIRED_FFMPEG} confirmed."
    else
        warn "ffmpeg found but version ${REQUIRED_FFMPEG} recommended."
        echo "  Found: $FOUND_FF"
        echo "  [1] Install ffmpeg ${REQUIRED_FFMPEG} from apt (may be older)"
        echo "  [2] Keep current version and continue"
        read -rp "Enter choice (1/2): " FF_CHOICE
        if [ "$FF_CHOICE" = "1" ]; then
            sudo apt-get install -y ffmpeg
            ok "ffmpeg updated via apt."
        else
            warn "Keeping current ffmpeg. Should still work for most features."
        fi
    fi
else
    warn "ffmpeg not found."
    read -rp "Install ffmpeg via apt now? (y/n): " CONFIRM_FF
    if [[ "$CONFIRM_FF" =~ ^[Yy]$ ]]; then
        sudo apt-get install -y ffmpeg
        ok "ffmpeg installed."
    else
        warn "ffmpeg skipped. Audio analysis will use fallback."
    fi
fi
echo ""

# =============================================================================
# STEP 4 - ANDROID STUDIO (MANUAL - LINUX NOTE)
# =============================================================================
echo "[4/6] Checking Android Studio..."
echo ""

AS_FOUND=0
if [ -f "$HOME/android-studio/bin/studio.sh" ]; then AS_FOUND=1; fi
if [ -f "/opt/android-studio/bin/studio.sh" ]; then AS_FOUND=1; fi
if command -v android-studio &>/dev/null; then AS_FOUND=1; fi

if [ "$AS_FOUND" = "0" ]; then
    echo "================================================"
    echo "  ANDROID STUDIO - MANUAL INSTALL REQUIRED"
    echo "================================================"
    echo ""
    echo "  Required : Panda 1  |  2025.3.1 Patch 1"
    echo ""
    echo "  Download:"
    echo "    https://developer.android.com/studio/archive"
    echo "    Find   : Android Studio Panda | 2025.3.1"
    echo "    Choose : Linux (.tar.gz)"
    echo ""
    echo "  Install steps on Linux Mint:"
    echo "    1. Extract the .tar.gz:"
    echo "       tar -xzf android-studio-*.tar.gz -C ~/  "
    echo "    2. Run the setup wizard:"
    echo "       ~/android-studio/bin/studio.sh"
    echo "    3. Set ANDROID_HOME after install:"
    echo "       echo 'export ANDROID_HOME=\$HOME/Android/Sdk' >> ~/.bashrc"
    echo "       echo 'export PATH=\$PATH:\$ANDROID_HOME/platform-tools' >> ~/.bashrc"
    echo "       source ~/.bashrc"
    echo ""
    echo "================================================"
else
    ok "Android Studio installation detected."
    warn "Please verify version manually: Help > About > confirm 2025.3.x (Panda 1)"
fi
echo ""

# =============================================================================
# STEP 5 - PYTHON PACKAGES
# =============================================================================
echo "[5/6] Installing Python packages from requirements.txt..."
echo "      This may take 3-5 minutes on first run..."
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$SCRIPT_DIR/backend/requirements.txt" ]; then
    fail "backend/requirements.txt not found."
    echo "     Make sure you run setup.sh from the root ChildFocus folder."
    exit 1
fi

cd "$SCRIPT_DIR/backend"
$PYTHON -m pip install --upgrade pip -q
$PYTHON -m pip install -r requirements.txt
ok "All Python packages installed."
echo ""

# =============================================================================
# STEP 6 - .ENV + VERIFY IMPORTS
# =============================================================================
echo "[6/6] Checking .env and verifying packages..."

if [ ! -f ".env" ]; then
    info "No .env file found. Creating template..."
    cat > .env <<'EOF'
YOUTUBE_API_KEY=your_youtube_api_key_here
FLASK_ENV=development
FLASK_DEBUG=1
EOF
    echo ""
    echo "[ACTION REQUIRED] Open backend/.env and replace:"
    echo "   your_youtube_api_key_here"
    echo "   with your YouTube Data API v3 key from:"
    echo "   https://console.cloud.google.com/apis/credentials"
else
    if grep -q "your_youtube_api_key_here" .env; then
        warn ".env exists but API key is still a placeholder."
        echo "     Open backend/.env and fill in your real YouTube API key."
    else
        ok ".env file found and API key is set."
    fi
fi
echo ""

$PYTHON -c "import flask, cv2, librosa, yt_dlp, numpy, requests, dotenv; print('[OK] All core packages verified.')" \
    || warn "Some packages may not have installed correctly. Try: pip install -r requirements.txt"
echo ""
cd "$SCRIPT_DIR"

# =============================================================================
# DONE
# =============================================================================
echo ""
echo "================================================"
echo "  ChildFocus Setup - COMPLETE"
echo "================================================"
echo ""
echo "------------------------------------------"
echo "  RUNNING CHILDFOCUS (Linux)"
echo "------------------------------------------"
echo ""
echo "  STEP 1 - Open a new terminal tab"
echo ""
echo "  STEP 2 - Navigate to the backend folder:"
echo "           cd $(pwd)/backend"
echo ""
echo "  STEP 3 - Start the server:"
echo "           python3.13 run.py"
echo "           Wait for:  * Running on http://127.0.0.1:5000"
echo ""
echo "  STEP 4 - Test (in a SECOND terminal):"
echo ""
echo "  curl -X POST http://localhost:5000/classify_full \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"video_url\":\"https://www.youtube.com/watch?v=pkD3Q2bpsqs\",\"thumbnail_url\":\"https://i.ytimg.com/vi/pkD3Q2bpsqs/hqdefault.jpg\"}'"
echo ""
echo "  STEP 5 - Stop the server:"
echo "           Ctrl + C"
echo ""
echo "------------------------------------------"
echo "  TROUBLESHOOTING"
echo "------------------------------------------"
echo ""
echo "  python3.13 not found  > sudo add-apt-repository ppa:deadsnakes/ppa"
echo "                           sudo apt install python3.13"
echo ""
echo "  pip install fails     > Try: sudo $PYTHON -m pip install -r requirements.txt"
echo ""
echo "  ANDROID_HOME not set  > echo 'export ANDROID_HOME=\$HOME/Android/Sdk' >> ~/.bashrc"
echo "                           source ~/.bashrc"
echo ""
echo "  Port 5000 in use      > sudo lsof -i :5000  then kill the PID,"
echo "                           or change port in backend/run.py"
echo ""
echo "================================================"
