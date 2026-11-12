# StepUp-X-Google-Fit

<p align="center">
  <img src="https://img.shields.io/badge/Python-3.10%2B-blue?style=for-the-badge&logo=python&logoColor=white" alt="Python 3.10+" />
  <img src="https://img.shields.io/badge/Google%20Fitness%20API-v1-4285F4?style=for-the-badge&logo=google&logoColor=white" alt="Google Fit API" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License: MIT" />
  <img src="https://img.shields.io/badge/StepUp-Compatible-orange?style=for-the-badge" alt="StepUp Compatible" />
</p>

Automated, cloud-level step synchronizer and walking cadence injector for **Google Fit**, **Fitbit**, and connected social fitness apps like **StepUp**.

Injects authentic human walking cadences directly through the official Google Fitness REST API with zero risk to phone hardware, zero emulator overhead, and built-in anti-cheat detection bypass.

---

## 🌟 Key Features

* **Natural Human Cadence (Gaussian Pacing):** Emulates true human walking cadence (~100–108 steps/min) with normal Gaussian distribution and slight micro-variations. Never injects unrealistic flat step blocks that trigger leaderboard anomaly bans.
* **Real-Time Live Walker:** Streams steps live in real-time intervals (every 4–5 seconds) with live terminal telemetry, speed feedback, and elapsed workout timers.
* **Instant Turbo Backfill:** Safely injects large step targets (e.g., 5,000 to 25,000+ steps) spread naturally across unoccupied 1-minute historical time slices of today.
* **Automated Workout Sessions:** Pairs every step batch with corresponding `com.google.activity.segment` (`activityType: 7` - Walking) and creates official `Outdoor Walk` sessions in `users/me/sessions`.
* **Zero-Lag Cloud Step Purge (`reset_steps.py`):** Made a mistake or added too many steps? Wipes today's injected sessions and raw step datasets back to zero in seconds.
* **Dynamic Device Stream Discovery:** Automatically discovers your phone's hardware model (e.g. Samsung Galaxy, Google Pixel) or creates matching phone pedometer streams so Google Fit mobile UI refreshes immediately.
* **Security & Privacy First:** Zero hardcoded credentials or client secrets. Authenticates via local OAuth 2.0 PKCE / Desktop flow and stores tokens locally under `.gitignore`.

---

## 🏗️ Architecture & How It Works

```
┌─────────────────────────────────┐
│     StepUp-X-Google-Fit CLI     │
│  (Real-Time / Instant Backfill) │
└────────────────┬────────────────┘
                 │
                 │ 1. OAuth 2.0 Bearer Token (fitness.activity.write)
                 ▼
┌────────────────────────────────────────────────────────┐
│             Google Fitness Cloud REST API              │
│  • Users DataSources (com.google.step_count.delta)     │
│  • Activity Segments (com.google.activity.segment)     │
│  • Workout Sessions (users/me/sessions)                │
└────────────────┬───────────────────────────────────────┘
                 │
                 │ 2. Aggregates into derived:estimated_steps
                 ▼
┌─────────────────────────────────┐      ┌───────────────────────────────┐
│         Google Fit App          │ ───► │          StepUp App           │
│   (Journal & Daily Step Ring)   │      │  (Friends & Group Leaderboard)│
└─────────────────────────────────┘      └───────────────────────────────┘
```

1. **Dataset Ingestion:** The script formats steps into nanosecond timestamp intervals (`minStartTimeNs` $\rightarrow$ `maxEndTimeNs`).
2. **Session Pairing:** An activity session is committed to Google's session store, marking the interval as an active walking workout.
3. **Downstream Aggregation:** Google Fitness Cloud merges the data points into the user's `derived:com.google.step_count.delta:com.google.android.gms:estimated_steps` ledger.
4. **Leaderboard Update:** StepUp pulls the updated cloud totals from Google Fit upon pull-to-refresh.

---

## 📋 Prerequisites

* **Operating System:** Windows, macOS, or Linux.
* **Python:** Version 3.10 or higher.
* A standard **Google Account** connected to Google Fit and StepUp.

---

## 🚀 Step-by-Step Setup Guide

### 1. Clone the Repository

```bash
git clone https://github.com/REP-Julian/StepUp-X-Google-Fit.git
cd StepUp-X-Google-Fit
```

### 2. Install Required Dependencies

```bash
pip install -r requirements.txt
```

---

### 3. Setup Google Cloud OAuth Credentials

To interact with your own Google Fit account, you need a free OAuth 2.0 Client ID from Google Cloud Console:

1. Open [Google Cloud Console](https://console.cloud.google.com/) and sign in with the Google account used on your phone.
2. Click **Select a project** $\rightarrow$ **New Project**, name it `StepUp-Sync`, and click **Create**.
3. **Enable Fitness API:**
   * Go to **APIs & Services** $\rightarrow$ **Library**.
   * Search for `Fitness API`.
   * Click **Fitness API** $\rightarrow$ click **Enable**.
4. **Configure OAuth Consent Screen:**
   * Go to **APIs & Services** $\rightarrow$ **OAuth consent screen**.
   * Select **External** $\rightarrow$ click **Create**.
   * Fill in **App name** (e.g. `StepUp Sync Client`) and your **User support email**.
   * Click **Save and Continue**.
   * On the **Scopes** page, click **Add or Remove Scopes**:
     * Search for `fitness` and check:
       * `https://www.googleapis.com/auth/fitness.activity.write`
       * `https://www.googleapis.com/auth/fitness.activity.read`
       * `https://www.googleapis.com/auth/fitness.body.write`
       * `https://www.googleapis.com/auth/fitness.location.write`
     * Click **Update** $\rightarrow$ **Save and Continue**.
   * Under **Test Users**:
     * Click **+ Add Users** and type your Google email address.
     * Click **Save and Continue**.
5. **Create Desktop OAuth Client ID:**
   * Go to **APIs & Services** $\rightarrow$ **Credentials**.
   * Click **+ Create Credentials** $\rightarrow$ **OAuth client ID**.
   * Set **Application type** to `Desktop app`.
   * Set **Name** to `StepUp Desktop Client` $\rightarrow$ click **Create**.
   * Click **Download JSON** on the newly created credential.
6. **Save to Project Root:**
   * Rename the downloaded JSON file to **`client_secret.json`** and move it into the root directory of this repository:
     ```
     StepUp-X-Google-Fit/
     ├── client_secret.json       <-- Place your downloaded file here
     ├── google_health_injector.py
     ├── human_injector.py
     ├── reset_steps.py
     └── ...
     ```

---

## 🎮 Usage

### Option A: Interactive Step Injector & Live Streamer (Recommended)

Runs the comprehensive interactive terminal dashboard with instant step presets, custom amounts, and real-time cadence streaming:

```bash
python google_health_injector.py
```
*(On Windows, you can also double-click `start_google_health.bat`)*

**First Run Authorization:**
On your first run, a browser tab will automatically open asking you to sign into your Google account and grant fitness permissions. Once approved, the script creates a secure `token.json` locally and refreshes it automatically.

---

### Option B: Real-Time Human Walker Engine

Simulates a live walking session feeding steps every 5 seconds with active cadence calculation:

```bash
python human_injector.py
```
*(Or double-click `start_injector.bat`)*

* **Mode 1 (Real-Time Live Walker):** Simulates step-by-step walking with live steps/min rate and elapsed timer. Press `Ctrl + C` anytime to pause and automatically save your workout session.
* **Mode 2 (Instant Turbo Backfill):** Instantly backfills your target goal across natural past minute slots.

---

### Option C: Quick 500 Steps Test

A fast one-shot script that uploads exactly 500 steps across the last 5 minutes to verify connectivity:

```bash
python test_500_steps.py
```
*(Or double-click `run_test_500.bat`)*

---

### Option D: 1-Tap Cloud Step Reset

Purges all injected walking sessions and step data points recorded today back to zero:

```bash
python reset_steps.py
```
*(Or double-click `start_reset.bat`)*

---

## 📱 Mobile Sync Troubleshooting (Zero-Lag Guide)

If steps show in Google Fitness Cloud but delay updating on your phone app:

### 1. Force Cloud Sync via the Journal Tab
* In the **Google Fit** phone app, tap **Journal** at the bottom $\rightarrow$ **swipe down to refresh**.
* *Note:* The Home tab pull-to-refresh only reads local sensor cache; the Journal pull-to-refresh forces an immediate download from Google servers.

### 2. Disable Hardware Pedometer Conflict (Samsung Galaxy & Android 14+)
* On Samsung phones, the hardware pedometer can temporarily override cloud entries for the current hour.
* To make cloud injections 100% immediate:
  1. Open **Google Fit** $\rightarrow$ tap **Profile** $\rightarrow$ tap **Settings** (gear icon).
  2. Toggle **OFF** **"Track your activities"** (or "Track activity metrics").
  3. Google Fit will now update instantly from the cloud upon launch.

### 3. StepUp Leaderboard Refresh
* Open **StepUp** $\rightarrow$ pull down on the friends leaderboard to pull latest totals from Google Fit.

---

## 🔒 Security & Privacy Notice

* **Never commit credentials:** `client_secret.json`, `google_health_client.json`, `google_health_token.json`, and `token.json` are explicitly ignored by `.gitignore`.
* **Personal Scopes:** The OAuth tokens generated are stored strictly on your local machine and communicate directly with Google's official endpoints over HTTPS (`oauth2.googleapis.com` and `fitness.googleapis.com`). No third-party servers are involved.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
