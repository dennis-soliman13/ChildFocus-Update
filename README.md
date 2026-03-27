# ChildFocus — Developer Setup Guide

---

## Requirements

Make sure these are installed before starting:

- [Python 3.10+](https://www.python.org/downloads/)
- [Node.js 18+](https://nodejs.org/)
- [Android Studio](https://developer.android.com/studio) (latest stable)
- [Git](https://git-scm.com/)

---

## 1. Clone the Repository

```bash
git clone https://github.com/nerusaur/ChildFocus-Update.git
cd ChildFocus-Update
```

---

## 2. Set Up the Python Backend

### Linux
```bash
cd backend
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Windows
```bat
cd backend
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

---

## 3. Set Up yt-dlp

yt-dlp needs Node.js to solve YouTube's signature challenges. Follow the steps for your OS.

---

### Linux

**Step 1 — Check where Node.js is installed:**
```bash
which node
```
Copy the path it gives you (e.g. `/home/yourname/.nvm/versions/node/v24.14.0/bin/node`).

**Step 2 — Create the yt-dlp config folder:**
```bash
mkdir -p ~/.config/yt-dlp
```

**Step 3 — Write the config** (replace the node path with yours from Step 1):
```bash
cat > ~/.config/yt-dlp/config << 'EOF'
--js-runtimes node:/home/yourname/.nvm/versions/node/v24.14.0/bin/node
--remote-components ejs:github
EOF
```

**Step 4 — Export cookies from your browser** (close Brave/Chrome first):
```bash
yt-dlp --cookies-from-browser brave \
       --cookies /path/to/ChildFocus-Update/backend/cookies.txt \
       --skip-download \
       "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

> Replace `brave` with `chrome` if you use Chrome.
> Replace `/path/to/` with the actual full path to your cloned repo.

---

### Windows

**Step 1 — Open the yt-dlp config folder:**

1. Press `Windows + R`, type `%APPDATA%`, press **Enter**
2. Look for a folder named `yt-dlp` — if it doesn't exist, create it:
   Right-click → **New → Folder** → name it `yt-dlp`
3. Open the `yt-dlp` folder
4. Right-click → **New → Text Document** → name it `config.txt`

   > ⚠️ Make sure it is not accidentally named `config.txt.txt`.
   > To check: File Explorer → View → tick **File name extensions**

**Step 2 — Open `config.txt` with Notepad and paste:**
```
--js-runtimes node:C:\Program Files\nodejs\node.exe
--remote-components ejs:github
```
Save and close.

**Step 3 — Export cookies from your browser** (close Brave/Chrome first, then open Command Prompt):
```bat
yt-dlp --cookies-from-browser brave --cookies C:\path\to\ChildFocus-Update\backend\cookies.txt --skip-download "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

> Replace `brave` with `chrome` if you use Chrome.
> Replace `C:\path\to\` with the actual path to your cloned repo.

---

### Verify yt-dlp Works (Linux and Windows)

```bash
yt-dlp --skip-download "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

✅ You should see this line in the output — no errors:
```
[youtube] [jsc:node] Solving JS challenges using node
```

---

## 4. Set Up the Android App

1. Open **Android Studio**
2. Click **Open** → select the `app/` folder inside the repo
3. Wait for Gradle to sync
4. Open `ChildFocusAccessibilityService.kt` and set the correct Flask server IP:
   - **Emulator:** `http://10.0.2.2:5000`
   - **Physical device:** `http://192.168.X.X:5000` ← your PC's local IP
5. Connect your Android device or start an emulator
6. Click **Run**

---

## 5. Environment Variables

Create a `.env` file inside the `backend/` folder. **Do not commit this file.**

```env
YOUTUBE_API_KEY=your_key_here
FLASK_HOST=0.0.0.0
FLASK_PORT=5000
```

Confirm these are in `.gitignore`:
```
backend/.env
backend/cookies.txt
```

---

## 6. Run the Flask Backend

### Linux
```bash
cd backend
source venv/bin/activate
python run.py
```

### Windows
```bat
cd backend
venv\Scripts\activate
python run.py
```

Server starts at `http://127.0.0.1:5000`.
