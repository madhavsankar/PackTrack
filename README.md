# 🎒 PackTrack

An open-source, AI-powered trip packing and lifecycle tracking assistant backed by **Google Gemini** (`v1beta/models` dynamic cascade & Structured Outputs), **Open-Meteo Weather**, **Android Room SQLite**, **Firebase Cloud Firestore**, **Cloudflare Workers**, and a **Model Context Protocol (MCP) Server**.

---

## Features

- **4-Screen Native Android & Cloudflare Edge Web Flow:**
  - **Home Dashboard (`HomeScreen.kt` / `WebAppView.js`):** View or delete saved trip plans, switch user profiles (`👤 Switch`), manage your Default Template, or start a new trip with Gemini.
  - **3-Stage Gemini Trip Interview (`GeminiInterviewScreen.kt`):** Interactive voice/text/chip interview covering **Stage 1** (Destination & exact Departure/Return Dates + live Open-Meteo weather lookup), **Stage 2** (Transport Mode: Own Vehicle vs. Rental Vehicle vs. No Car/Transit, and Work vs. Leisure), and **Stage 3** (Luggage Space: Personal Item Only vs. Carry-On vs. Checked Bag vs. 2 Checked Bags).
  - **Trip Checklist (`TripChecklistScreen.kt`):** Live weather banner with 1-tap refresh (`🔄 Refresh`), collapsible AI-Suggested Additions card (`+ Add` / `✕`), visible fallback warning banner if Gemini is offline (`⚠️ Gemini is unavailable — rule-based backup results were used`), manual item edits, and **long-press on any item** to save it permanently to your Default Template (`⭐ Add to Default Template`).
  - **Default Template Editor (`TemplateEditorScreen.kt`):** View, add, or remove permanent rules (with confirmation prompts) across built-in and custom user-created categories (`On Me`, `Docs`, `Electronics`, `Toiletry`, `Clothes`, `Medicines`, `Others`, plus any custom category you create). Supports arbitrary natural-language rule conditions (e.g., *"Only add this when there is at least one rainy day and if trip is for more than 5 days and only when we go to India"*).
- **Generalized Live Gemini Model Cascade & Structured Output:**
  - Dynamically discovers available models via `GET /v1beta/models` (cached 1h), filters out non-text modalities (`-image`, `nano-banana`, `omni`, `tts`, `transcribe`, `live`, `gemma`) and legacy `< 3.0` models, and ranks general text models in tier-first order (`gemini-flash-latest` → `gemini-3.8-flash` → `gemini-3.6-flash` → `gemini-3.5-flash-lite` → `gemini-3.1-flash-lite` → `gemini-flash-lite-latest` → `gemini-4-argon`).
  - Enforces strict JSON schema validation via `generationConfig.responseSchema` (`responseMimeType: "application/json"`), dynamically injecting user-created custom categories into the schema `enum`.
- **3-Phase Trip Lifecycle Modes:**
  - **🧳 Pack Outbound:** Full categorized checklist with weather- and duration-scaled clothing counts (`Personal`, `Carry-On`, `Checked Bag`).
  - **🚪 60-Second Doorway Check:** Filters to must-have departure essentials right before walking out the door.
  - **🏨 Hotel Checkout Sweep:** Separate return-trip checkmarks (`packedReturn`) that pin high-loss items (chargers, travel documents) to the top.
- **Public Gemini App & Home Widget Integration:** Share trip plans directly from the Android Gemini app via the system Share Sheet (`ACTION_SEND`), launch via `packtrack://create` deep links, or use the 1-tap Home Screen voice widget.
- **Google Sign-In & Email Allowlist Security:** Built-in Google OAuth Sign-In (`GOOGLE_CLIENT_ID`) on both Web (`accounts.google.com/gsi/client`) and Android (`play-services-auth` `GoogleSignIn`), backed by server-side JWT verification (`oauth2.googleapis.com/tokeninfo`) and `ALLOWED_EMAILS` allowlist enforcement.
- **Multi-Tenant Offline-First + Cloud Sync:** Works 100% offline on flights using local SQLite (`Room` on Android / `packtrack.db` in Python), syncs per-user profiles to **Firebase Cloud Firestore** (`packtrack_state/user_state_{userId}`), and packages password-protected release-signed APK builds (`PackTrack-Protected.zip`).

---

## High-Level Design (HLD) & Architecture

### 1. System Architecture

```mermaid
flowchart TB
    subgraph Clients["User Touchpoints and Clients"]
        AndroidUI["Native Android App (HomeScreen, GeminiInterviewScreen, TripChecklistScreen, TemplateEditorScreen)"]
        Widget["Android Home Widget and Share Target (ACTION_SEND, packtrack://create)"]
        EdgePWA["Cloudflare Edge Web App (cloudflare/src/ui/WebAppView.js)"]
        ExternalMCP["External MCP Clients (Gemini CLI, Claude, IDE Agents)"]
    end

    subgraph AndroidRuntime["Android Application Layer (android/app/src/main/java/com/packtrack/app/)"]
        MainAct["MainActivity (Intent Router, Room Init, Firebase Analytics, TTS)"]
        VoiceAsst["GeminiVoiceAssistant (agent/GeminiVoiceAssistant.kt)"]
        Calc["ChecklistCalculator (domain/ChecklistCalculator.kt)"]
        Catalog["TravelerTemplateCatalog (domain/TravelerTemplateCatalog.kt)"]
        WeatherKt["OpenMeteoWeatherService (data/OpenMeteoWeatherService.kt)"]
        SyncKt["FirestoreSyncService (data/FirestoreSyncService.kt)"]
    end

    subgraph EdgeRuntime["Cloudflare Worker Edge Runtime (cloudflare/)"]
        HttpRouter["HttpRouter (controllers/HttpRouter.js)"]
        EdgeEngine["PackingEngineService (services/PackingEngineService.js)"]
        EdgeMcp["McpRouter JSON-RPC 2.0 (mcp/McpRouter.js)"]
        FirestoreRepo["FirestoreRepository (repositories/FirestoreRepository.js)"]
    end

    subgraph ExternalAPIs["External AI and Weather APIs"]
        ModelsAPI["Gemini Model Discovery (GET /v1beta/models)"]
        GeminiCascade["Gemini Cascade (gemini-3.8-flash -> gemini-3.6-flash -> gemini-3.5-flash-lite -> gemini-3.1-flash-lite)"]
        RuleFallback["Multi-Clause Deterministic Rule Evaluator (ChecklistCalculator / PackingEngineService)"]
        OpenMeteo["Open-Meteo Geocoding and 16-Day Weather Forecast API"]
        ModelsAPI -->|"5-Stage Modality & Tier Scorer"| GeminiCascade
        GeminiCascade -->|"HTTP 429/5xx or Offline Fallback + Warning Banner"| RuleFallback
    end

    subgraph Storage["Local-First SQLite and Cloud Firestore NoSQL DB"]
        RoomDB["Android Room SQLite DB (packtrack_local.db: trips, packing_items, template_rules)"]
        Firestore["Firebase Cloud Firestore (packtrack_state/user_state_{userId})"]
    end

    Widget -->|"Intents and Deep Links"| MainAct
    MainAct --> AndroidUI
    AndroidUI -->|"Stage 1-3 Interview and Weather Refresh"| VoiceAsst
    AndroidUI <-->|"Reactive Kotlin Flows and Template Edits"| RoomDB

    VoiceAsst -->|"Structured Output JSON (responseSchema)"| GeminiCascade
    VoiceAsst -->|"Fetch Forecast by Dates"| WeatherKt
    VoiceAsst -->|"Fallback Checklist & Scaling"| Calc
    Calc -->|"Read Starter Rules"| Catalog
    WeatherKt -->|"HTTP GET"| OpenMeteo
    VoiceAsst -->|"Save Trip and Items"| RoomDB
    RoomDB <-->|"SyncKt: Pull and Push Per-User State"| Firestore

    EdgePWA -->|"REST /api/*"| HttpRouter
    ExternalMCP -->|"Streamable HTTP /mcp"| EdgeMcp
    HttpRouter --> EdgeEngine
    EdgeMcp --> EdgeEngine
    EdgeEngine -->|"Structured Output JSON (responseSchema)"| GeminiCascade
    EdgeEngine -->|"Fetch Forecast by Dates"| OpenMeteo
    EdgeEngine <--> FirestoreRepo
    FirestoreRepo <-->|"REST PATCH / GET"| Firestore
```

### 2. End-to-End Trip Interview, Unified Gemini Evaluation & Lifecycle Sequence

```mermaid
sequenceDiagram
    actor User
    participant UI as PackTrack UI (Android / Cloudflare Web)
    participant Engine as GeminiVoiceAssistant / PackingEngineService
    participant Weather as Open-Meteo API
    participant Gemini as Gemini Cascade (/v1beta/models -> generateContent)
    participant Calc as Deterministic Fallback (ChecklistCalculator)
    participant Store as Room SQLite + Cloud Firestore

    User->>UI: Tap "+ Create New Trip" -> Enter Destination & Exact Dates (e.g. "Mumbai, 2026-11-10 to 2026-11-17")
    UI->>Engine: Stage 1: fetchWeather("Mumbai", startDate="2026-11-10", endDate="2026-11-17")
    Engine->>Weather: Geocode + Fetch daily high/low temps and rainy days for exact dates
    Weather-->>UI: 74°F-86°F, 2 rainy days, Type C/D/M plug (Prompts Stage 2 & Stage 3)
    User->>UI: Select Transport (Rental Car), Purpose (Work), and Luggage (Carry-On)
    UI->>Engine: createAndSaveTrip(templateRules + tripContext + weatherForecast)
    Engine->>Gemini: Single Structured-Output Call (evaluate Default Template rules + suggest 2-5 weather/trip items)
    alt Gemini Cascade Succeeds (200 OK)
        Gemini-->>Engine: { matchedTemplateItems: [...], suggestedItems: [...] }
    else All Models in Cascade Fail / Offline
        Engine->>Calc: Multi-clause deterministic evaluation + weather suggestions ([FALLBACK] tag)
        Calc-->>Engine: Backup checklist + sets Warning Banner on UI
    end
    Engine->>Store: Save Trip + Active Items + [SUGGESTED] Items & Sync to Firestore
    Store-->>UI: Open TripChecklistScreen (Pack Outbound / Doorway Check / Hotel Checkout modes)
```

---

## Quick Start

### 1. Native Android App (`android/`)

```bash
cd android
# Optional in git-ignored android/local.properties:
# GEMINI_API_KEY=your_gemini_api_key
# FIREBASE_PROJECT_ID=your_firebase_project_id
# DEFAULT_USER_ID=your_username_or_email
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Deploy Cloudflare Worker Web App (`cloudflare/`)

1. Connect this repository in **Cloudflare Workers & Pages** (or deploy via Wrangler CLI):
   ```bash
   npx wrangler secret put FIREBASE_PROJECT_ID
   npx wrangler secret put GEMINI_API_KEY
   npx wrangler secret put GOOGLE_CLIENT_ID
   npx wrangler secret put ALLOWED_EMAILS
   npx wrangler deploy
   ```
   *(Note: `keep_vars = true` is enabled in [`wrangler.toml`](wrangler.toml) so git pushes never overwrite variables or secrets configured in your Cloudflare Dashboard).*

### 3. Automated CI/CD & Password-Protected Signed APK (`.github/workflows/android-apk.yml`)

Every push to `main` builds a release-signed APK with an auto-incrementing `versionCode` (`1.0.<run_number>`), locks it inside a password-protected archive (`PackTrack-Protected.zip` encrypted with `KEYSTORE_PASSWORD`), and optionally distributes it via **Firebase App Distribution** when the following GitHub Actions secrets are configured:
- `ANDROID_KEYSTORE_BASE64` & `KEYSTORE_PASSWORD`
- `GOOGLE_CLIENT_ID`, `ALLOWED_EMAILS`, and `GOOGLE_SERVICES_JSON`
- `GEMINI_API_KEY` & `FIREBASE_PROJECT_ID`

### 4. Local Python Companion Server & Evals (`packtrack/` & `tests/`)

```bash
# Launch local companion web UI on http://localhost:8090
python3 -m packtrack.server

# Run all Python + Cloudflare JS engine unit tests and evals
python3 -m unittest discover -s tests -v
```

---

## Project Structure

```text
PackTrack/
├── android/               # Native Kotlin + Jetpack Compose Android App, Room DB, Firebase SDK & Home Widget
├── cloudflare/            # Serverless Cloudflare Worker Web App, REST API, Gemini Cascade & HTTP MCP Server
├── packtrack/             # Python Core Rule Engine, Open-Meteo Service, Companion Web UI & stdio MCP Server
└── tests/                 # Unit tests & evals for work/laptop rules, compound rules, JSON schemas & model cascade
```

---

## License

MIT
