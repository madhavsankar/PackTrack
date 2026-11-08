# PackTrack: System Design & Implementation Plan

## 1. Overview & Architecture
**PackTrack** is a hybrid **AI Agent Plugin (MCP + REST API) + Companion Mobile App** inspired by PackPoint, upgraded with conversational gap-analysis and full trip-lifecycle tracking (Pre-departure doorway check, Outbound packing, and Return/Hotel Checkout sweep).

### Tech Stack (Python + Kotlin)
1. **Backend / Core Engine & MCP Plugin (`backend/` — Python)**:
   - **Core Packing & Weather Engine (`packtrack/engine.py`, `packtrack/weather.py`)**:
     - Geocoding & live weather forecasts / climate averages via **Open-Meteo API** (no API key required).
     - Destination intelligence (International detection, plug adapter detection, currency/documents).
     - **PackPoint-style Rule + Multiplier Engine**: Calculates exact clothing counts based on trip duration, laundry preferences, temperature bands, rain/snow probability, and selected activity modules.
     - **Conversational Gap Analyzer**: Compares trip context with the user's Traveler Template and generates 3–5 targeted clarifying questions before finalizing the list.
   - **Database & State (`packtrack/db.py`)**:
     - SQLite database storing `TravelerProfile` (templates & custom activity packs), `Trips`, and `PackingItems` (with both `packed_outbound` and `packed_return` states).
   - **MCP Server (`packtrack/mcp_server.py`) & REST API (`packtrack/server.py`)**:
     - Exposes MCP tools (`get_travel_profile`, `analyze_trip_and_ask_questions`, `generate_trip_checklist`, `toggle_item_status`, `switch_trip_mode`, `update_template_module`) for Claude and Gemini.
     - Serves a responsive **Mobile PWA / Companion Web UI** (`static/index.html`) so you can immediately test the 1-tap packing checklist, Weather Banner, "Doorway Check", and "Hotel Checkout Sweep" on your device or browser.
2. **Android Native App (`android/` — Kotlin + Jetpack Compose)**:
   - Clean MVVM architecture (`data`, `domain`, `ui`) in Kotlin with Jetpack Compose screens for the Trip Wizard, Activity Selector, Categorized Checklist, Doorway Check, and Gemini App Actions (`shortcuts.xml`).

---

## 2. Directory Structure
```text
PackTrack/
├── README.md
├── requirements.txt
├── packtrack/
│   ├── __init__.py
│   ├── models.py          # Data models for Profile, Templates, Trips, Items, Weather
│   ├── db.py              # SQLite persistence for profiles, templates, trips & items
│   ├── weather.py         # Open-Meteo geocoding & weather/climate lookup + country metadata
│   ├── engine.py          # PackPoint-style rule engine, gap-analysis Q&A, clothing calculator
│   ├── mcp_server.py      # Model Context Protocol (MCP) server for Claude / Gemini
│   ├── server.py          # FastAPI / HTTP server for REST API + Mobile Companion UI
│   └── static/
│       └── index.html     # Mobile-first interactive companion UI (PackPoint + Doorway + Return modes)
├── tests/
│   └── test_engine.py     # Unit tests for weather rules, quantities, Q&A, and return tracking
└── android/               # Native Kotlin + Jetpack Compose Android project structure
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── res/xml/shortcuts.xml              # Gemini on Android App Actions integration
        └── java/com/packtrack/app/
            ├── model/PackTrackModels.kt       # Shared Kotlin data classes
            ├── data/PackTrackRepository.kt    # Syncs with Python backend + local Room state
            └── ui/ChecklistScreen.kt          # Jetpack Compose UI (Weather banner, Modes, Categories)
```

