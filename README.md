# 🎒 PackTrack

An open-source, AI-powered trip packing and lifecycle tracking assistant backed by **Google Gemini** (`v1beta/interactions`), **Open-Meteo Weather**, **Android Room SQLite**, **Firebase Cloud Firestore**, and a **Model Context Protocol (MCP) Server**.

---

## Features

- **4-Screen Native Android & Companion Web Flow:**
  - **Home Dashboard (`HomeScreen.kt`):** View or delete saved trip plans, switch user profiles (`👤 Switch`), manage your Default Template, or start a new trip with Gemini.
  - **3-Stage Gemini Trip Interview (`GeminiInterviewScreen.kt`):** Interactive voice/text/chip interview covering **Stage 1** (Destination & Duration + live weather lookup), **Stage 2** (Transport Mode: Own Vehicle vs. Rental Vehicle vs. Transit, and Work vs. Leisure), and **Stage 3** (Bag Space: Personal Item Only vs. Carry-On vs. Checked Suitcase).
  - **Trip Checklist (`TripChecklistScreen.kt`):** Live weather banner with 1-tap refresh (`🔄 Refresh`), manual item edits, and **long-press on any item** to save it permanently to your Default Template (`⭐ Add to Default Template`).
  - **Default Template Editor (`TemplateEditorScreen.kt`):** View, add, or remove permanent rules across 7 categories (`Basics`, `Documents`, `Office / Work`, `Clothes`, `Tech & Chargers`, `Toiletries & Health`, `Packing & Travel Gear`).
- **3-Phase Trip Lifecycle Modes:**
  - **🧳 Pack Outbound:** Full categorized checklist with weather- and duration-scaled clothing counts.
  - **🚪 60-Second Doorway Check:** Filters to must-have departure essentials right before walking out the door.
  - **🏨 Hotel Checkout Sweep:** Separate return-trip checkmarks (`packedReturn`) that pin high-loss items (chargers, travel documents) to the top.
- **Public Gemini App & Home Widget Integration:** Share trip plans directly from the Android Gemini app via the system Share Sheet (`ACTION_SEND`), launch via `packtrack://create` deep links, or use the 1-tap Home Screen widget.
- **Multi-Tenant Offline-First + Cloud Sync:** Works 100% offline on flights using local SQLite (`Room` on Android / `packtrack.db` in Python) and syncs per-user profiles to **Firebase Cloud Firestore** (`packtrack_state/user_state_{userId}`).

---

## High-Level Design (HLD) & Architecture

### 1. System Architecture

```mermaid
flowchart TB
    subgraph Clients["User Touchpoints and Clients"]
        AndroidUI["Native Android App (HomeScreen, GeminiInterviewScreen, TripChecklistScreen, TemplateEditorScreen)"]
        Widget["Android Home Widget and Share Target (ACTION_SEND, packtrack://create)"]
        WebUI["Companion Web UI (packtrack/static/index.html)"]
        ExternalMCP["External MCP Clients (Gemini CLI, Claude, IDE Agents)"]
    end

    subgraph AndroidRuntime["Android Application Layer (android/app/src/main/java/com/packtrack/app/)"]
        MainAct["MainActivity (Intent Router, Room Init, TTS)"]
        VoiceAsst["GeminiVoiceAssistant (agent/GeminiVoiceAssistant.kt)"]
        Calc["ChecklistCalculator (domain/ChecklistCalculator.kt)"]
        Catalog["TravelerTemplateCatalog (domain/TravelerTemplateCatalog.kt)"]
        WeatherKt["OpenMeteoWeatherService (data/OpenMeteoWeatherService.kt)"]
        SyncKt["FirestoreSyncService (data/FirestoreSyncService.kt)"]
    end

    subgraph PythonRuntime["Python Core and MCP Server (packtrack/)"]
        PyServer["HTTP Server and REST API (packtrack/server.py)"]
        McpSrv["MCP JSON-RPC 2.0 Server (packtrack/mcp_server.py)"]
        PyAgent["GeminiVoiceAgent (packtrack/gemini_agent.py)"]
        PyEngine["Rule and Gap Engine (packtrack/engine.py)"]
    end

    subgraph ExternalAPIs["External AI and Weather APIs"]
        GeminiAPI["Google Gemini Interactions API (v1beta/interactions - gemini-3.8-flash)"]
        LocalNLP["On-Device Smart Voice and Rule Fallback Parser"]
        OpenMeteo["Open-Meteo Geocoding and 14-Day Weather Forecast API"]
        GeminiAPI -->|"Offline or No-Key Fallback"| LocalNLP
    end

    subgraph Storage["Local-First SQLite and Cloud Firestore NoSQL DB"]
        RoomDB["Android Room SQLite DB (packtrack_local.db: trips, packing_items, template_rules)"]
        PyDB["Python SQLite DB (packtrack.db: profiles, trips, packing_items)"]
        Firestore["Firebase Cloud Firestore (packtrack_state/user_state_{userId})"]
    end

    Widget -->|"Intents and Deep Links"| MainAct
    MainAct --> AndroidUI
    AndroidUI -->|"Stage 1-3 Interview and Weather Refresh"| VoiceAsst
    AndroidUI <-->|"Reactive Kotlin Flows and Long-Press Template Edits"| RoomDB

    VoiceAsst -->|"Function Calling"| GeminiAPI
    VoiceAsst -->|"Fetch Forecast"| WeatherKt
    VoiceAsst -->|"Synthesize Checklist"| Calc
    Calc -->|"Read Starter Rules"| Catalog
    WeatherKt -->|"HTTP GET"| OpenMeteo
    VoiceAsst -->|"Save Trip and Items"| RoomDB
    RoomDB <-->|"SyncKt: Pull and Push Per-User State"| Firestore

    WebUI -->|"REST /api/*"| PyServer
    ExternalMCP -->|"stdio JSON-RPC 2.0"| McpSrv
    PyServer --> PyAgent
    PyAgent --> GeminiAPI
    PyAgent --> McpSrv
    McpSrv --> PyEngine
    PyEngine <--> PyDB
    PyDB <-->|"Optional Cloud Sync"| Firestore
```

### 2. End-to-End Trip Interview & Lifecycle Sequence

```mermaid
sequenceDiagram
    actor User
    participant UI as PackTrack UI (HomeScreen / Interview / Checklist)
    participant Asst as GeminiVoiceAssistant
    participant Weather as OpenMeteoWeatherService
    participant Calc as ChecklistCalculator
    participant Room as Room SQLite (packtrack_local.db)
    participant Cloud as Cloud Firestore (user_state_{userId})

    User->>UI: Tap "Plan New Trip with Gemini" -> "Seattle for 5 days"
    UI->>Asst: Stage 1: fetchLiveForecast("Seattle", days=5)
    Asst->>Weather: Geocode + Fetch 5-day high/low temps and rain days
    Weather-->>UI: 44°F-56°F, 3 rainy days (Prompts Stage 2: Transport & Work)
    User->>UI: Stage 2: Select "Rental Vehicle + Work" -> Stage 3: Select "Carry-On"
    UI->>Asst: createAndSaveTrip(city="Seattle", days=5, isWork=true, carMode=RENTAL_CAR, bag=CARRY_ON)
    Asst->>Room: Load active user's TemplateRuleEntity rules
    Asst->>Calc: buildChecklistForTrip(userRules + weather scaling + vehicle rules)
    Calc-->>Asst: Return categorized PackingItemEntity list
    Asst->>Room: Insert TripEntity + PackingItemEntity rows
    Asst->>Cloud: pushLocalStateToFirestore(userId)
    Room-->>UI: Navigate to TripChecklistScreen (Pack / Doorway / Checkout modes)
    User->>UI: Long-press item -> Tap "Add to Default Template"
    UI->>Room: Insert TemplateRuleEntity for active userId
    UI->>Cloud: Sync updated template rules to Firestore
```

---

## Quick Start

### 1. Native Android App (`android/`)

```bash
cd android
# Optional in git-ignored android/local.properties:
# GEMINI_API_KEY=your_gemini_api_key
# FIREBASE_PROJECT_ID=your_firebase_project_id
# DEFAULT_USER_ID=your_email_or_profile_id
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Deploy Cloudflare Worker Web App (`cloudflare/`)

1. Connect this repository in **Cloudflare Workers & Pages** (or deploy via Wrangler CLI):
   ```bash
   npx wrangler secret put FIREBASE_PROJECT_ID
   npx wrangler deploy
   ```

### 3. Python Companion Web UI & MCP Server (`packtrack/`)

```bash
# Launch local web UI on http://localhost:8090
python3 -m packtrack.server

# Run automated unit tests
python3 -m unittest discover -s tests -v
```

---

## Project Structure

```text
PackTrack/
├── android/               # Native Kotlin + Jetpack Compose Android App, Room DB & Home Widget
├── cloudflare/            # Serverless Cloudflare Worker Web App, REST API & HTTP MCP Server
├── packtrack/             # Python Core Rule Engine, Open-Meteo Service, Web UI & MCP Server
└── tests/                 # Unit tests for weather, template rules, and voice agent workflow
```

---

## License

MIT
