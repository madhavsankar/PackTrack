"""SQLite persistence layer for PackTrack Profiles, Templates, Trips, and Packing Items."""

import json
import os
import re
import sqlite3
import urllib.request
from datetime import datetime
from typing import Dict, Any, List, Optional
from .models import DEFAULT_TRAVELER_PROFILE, DestinationWeatherSummary


DEFAULT_DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "packtrack.db")
FIREBASE_PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID", "")


def sanitize_user_id(user_id: Optional[str] = "default") -> str:
    trimmed = (user_id or "").strip().lower()
    if not trimmed:
        return "default"
    cleaned = re.sub(r"[^a-z0-9_.-]", "_", trimmed)[:64]
    return cleaned or "default"


def firestore_doc_url(user_id: str = "default") -> Optional[str]:
    if not FIREBASE_PROJECT_ID:
        return None
    clean = sanitize_user_id(user_id)
    doc_id = "user_state" if clean == "default" else f"user_state_{clean}"
    return (
        f"https://firestore.googleapis.com/v1/projects/{FIREBASE_PROJECT_ID}"
        f"/databases/(default)/documents/packtrack_state/{doc_id}"
    )


class PackTrackDB:
    def __init__(self, db_path: str = DEFAULT_DB_PATH):
        self.db_path = db_path
        self._init_schema()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_schema(self):
        with self._conn() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS profiles (
                    user_id TEXT PRIMARY KEY,
                    profile_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS trips (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    destination TEXT NOT NULL,
                    country TEXT NOT NULL,
                    start_date TEXT NOT NULL,
                    duration_days INTEGER NOT NULL,
                    trip_mode TEXT NOT NULL DEFAULT 'OUTBOUND', -- 'DOORWAY', 'OUTBOUND', 'RETURN_SWEEP'
                    tags_json TEXT NOT NULL,
                    weather_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS packing_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trip_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    bag_location TEXT NOT NULL DEFAULT 'Carry-On',
                    packed_outbound INTEGER NOT NULL DEFAULT 0,
                    packed_return INTEGER NOT NULL DEFAULT 0,
                    is_doorway_critical INTEGER NOT NULL DEFAULT 0,
                    is_high_loss_return INTEGER NOT NULL DEFAULT 0,
                    weather_reason TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE
                );
                """
            )
            row = conn.execute("SELECT user_id FROM profiles WHERE user_id = 'default'").fetchone()
            if not row:
                conn.execute(
                    "INSERT INTO profiles (user_id, profile_json, updated_at) VALUES (?, ?, ?)",
                    ("default", json.dumps(DEFAULT_TRAVELER_PROFILE), datetime.utcnow().isoformat()),
                )

    def get_profile(self, user_id: str = "default") -> Dict[str, Any]:
        with self._conn() as conn:
            row = conn.execute("SELECT profile_json FROM profiles WHERE user_id = ?", (user_id,)).fetchone()
            if row:
                return json.loads(row["profile_json"])
            return dict(DEFAULT_TRAVELER_PROFILE)

    def save_profile(self, profile: Dict[str, Any], user_id: str = "default") -> Dict[str, Any]:
        with self._conn() as conn:
            conn.execute(
                """
                INSERT INTO profiles (user_id, profile_json, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    profile_json = excluded.profile_json,
                    updated_at = excluded.updated_at
                """,
                (user_id, json.dumps(profile), datetime.utcnow().isoformat()),
            )
        return profile

    def add_template_item(
        self,
        module_or_section: str,
        item_name: str,
        category: str,
        is_doorway_critical: bool = False,
        is_high_loss_return: bool = True,
        user_id: str = "default",
    ) -> Dict[str, Any]:
        """Adds a permanent item to the user's template (either 'basics' or an activity module like 'work')."""
        profile = self.get_profile(user_id)
        new_entry = {
            "name": item_name,
            "category": category,
            "is_doorway_critical": is_doorway_critical,
            "is_high_loss_return": is_high_loss_return,
            "bag_location": "Personal Item" if is_doorway_critical else "Carry-On",
        }
        mod_key = module_or_section.lower().strip()
        if mod_key in ("basics", "always_pack", "always_pack_basics"):
            profile["always_pack_basics"].append(new_entry)
        elif mod_key in ("documents", "default_documents"):
            profile["default_documents"].append(new_entry)
        elif mod_key in ("tech", "default_tech"):
            profile["default_tech"].append(new_entry)
        else:
            if mod_key not in profile["activity_modules"]:
                profile["activity_modules"][mod_key] = {
                    "label": module_or_section.title(),
                    "description": f"Custom {module_or_section.title()} module",
                    "items": [],
                }
            profile["activity_modules"][mod_key]["items"].append(new_entry)
        return self.save_profile(profile, user_id)

    def create_trip(
        self,
        weather: DestinationWeatherSummary,
        tags: List[str],
        items: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        now = datetime.utcnow().isoformat()
        with self._conn() as conn:
            cur = conn.execute(
                """
                INSERT INTO trips (destination, country, start_date, duration_days, trip_mode, tags_json, weather_json, created_at)
                VALUES (?, ?, ?, ?, 'OUTBOUND', ?, ?, ?)
                """,
                (
                    weather.destination_name,
                    weather.country,
                    weather.start_date,
                    weather.duration_days,
                    json.dumps(tags),
                    json.dumps(weather.to_dict()),
                    now,
                ),
            )
            trip_id = cur.lastrowid
            for item in items:
                conn.execute(
                    """
                    INSERT INTO packing_items (
                        trip_id, name, category, quantity, bag_location,
                        packed_outbound, packed_return, is_doorway_critical,
                        is_high_loss_return, weather_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        trip_id,
                        item["name"],
                        item["category"],
                        item.get("quantity", 1),
                        item.get("bag_location", "Carry-On"),
                        1 if item.get("packed_outbound") else 0,
                        1 if item.get("packed_return") else 0,
                        1 if item.get("is_doorway_critical") else 0,
                        1 if item.get("is_high_loss_return") else 0,
                        item.get("weather_reason", ""),
                    ),
                )
        return self.get_trip(trip_id)

    def get_trip(self, trip_id: int) -> Optional[Dict[str, Any]]:
        with self._conn() as conn:
            t_row = conn.execute("SELECT * FROM trips WHERE id = ?", (trip_id,)).fetchone()
            if not t_row:
                return None
            i_rows = conn.execute(
                "SELECT * FROM packing_items WHERE trip_id = ? ORDER BY id ASC", (trip_id,)
            ).fetchall()

        items = [
            {
                "id": r["id"],
                "trip_id": r["trip_id"],
                "name": r["name"],
                "category": r["category"],
                "quantity": r["quantity"],
                "bag_location": r["bag_location"],
                "packed_outbound": bool(r["packed_outbound"]),
                "packed_return": bool(r["packed_return"]),
                "is_doorway_critical": bool(r["is_doorway_critical"]),
                "is_high_loss_return": bool(r["is_high_loss_return"]),
                "weather_reason": r["weather_reason"],
            }
            for r in i_rows
        ]
        total = len(items)
        packed_out = sum(1 for i in items if i["packed_outbound"])
        packed_ret = sum(1 for i in items if i["packed_return"])

        return {
            "id": t_row["id"],
            "destination": t_row["destination"],
            "country": t_row["country"],
            "start_date": t_row["start_date"],
            "duration_days": t_row["duration_days"],
            "trip_mode": t_row["trip_mode"],
            "tags": json.loads(t_row["tags_json"]),
            "weather": json.loads(t_row["weather_json"]),
            "created_at": t_row["created_at"],
            "stats": {
                "total_items": total,
                "packed_outbound": packed_out,
                "packed_return": packed_ret,
                "doorway_critical_total": sum(1 for i in items if i["is_doorway_critical"]),
                "doorway_critical_packed": sum(
                    1 for i in items if i["is_doorway_critical"] and i["packed_outbound"]
                ),
            },
            "items": items,
        }

    def list_trips(self) -> List[Dict[str, Any]]:
        with self._conn() as conn:
            rows = conn.execute("SELECT id FROM trips ORDER BY id DESC").fetchall()
        return [self.get_trip(r["id"]) for r in rows if r]

    def toggle_item(
        self,
        item_id: int,
        phase: str = "outbound",
        checked: Optional[bool] = None,
    ) -> Optional[Dict[str, Any]]:
        col = "packed_return" if phase.lower().startswith("ret") else "packed_outbound"
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM packing_items WHERE id = ?", (item_id,)).fetchone()
            if not row:
                return None
            new_val = (1 if checked else 0) if checked is not None else (0 if row[col] else 1)
            conn.execute(f"UPDATE packing_items SET {col} = ? WHERE id = ?", (new_val, item_id))
            trip_id = row["trip_id"]
        return self.get_trip(trip_id)

    def mark_items_by_name(
        self,
        trip_id: int,
        item_names: List[str],
        phase: str = "outbound",
        checked: bool = True,
    ) -> Optional[Dict[str, Any]]:
        col = "packed_return" if phase.lower().startswith("ret") else "packed_outbound"
        val = 1 if checked else 0
        with self._conn() as conn:
            rows = conn.execute("SELECT id, name FROM packing_items WHERE trip_id = ?", (trip_id,)).fetchall()
            for r in rows:
                for query_name in item_names:
                    if query_name.lower() in r["name"].lower():
                        conn.execute(f"UPDATE packing_items SET {col} = ? WHERE id = ?", (val, r["id"]))
        return self.get_trip(trip_id)

    def set_trip_mode(self, trip_id: int, mode: str) -> Optional[Dict[str, Any]]:
        valid_modes = {"OUTBOUND", "DOORWAY", "RETURN_SWEEP"}
        norm_mode = mode.upper()
        if norm_mode not in valid_modes:
            norm_mode = "OUTBOUND"
        with self._conn() as conn:
            conn.execute("UPDATE trips SET trip_mode = ? WHERE id = ?", (norm_mode, trip_id))
        return self.get_trip(trip_id)

    def add_custom_item_to_trip(
        self,
        trip_id: int,
        name: str,
        category: str,
        quantity: int = 1,
        is_doorway_critical: bool = False,
    ) -> Optional[Dict[str, Any]]:
        with self._conn() as conn:
            conn.execute(
                """
                INSERT INTO packing_items (
                    trip_id, name, category, quantity, bag_location,
                    packed_outbound, packed_return, is_doorway_critical,
                    is_high_loss_return, weather_reason
                ) VALUES (?, ?, ?, ?, 'Carry-On', 0, 0, ?, 1, 'Added by traveler')
                """,
                (trip_id, name, category, max(1, quantity), 1 if is_doorway_critical else 0),
            )
        trip = self.get_trip(trip_id)
        self.sync_to_firestore()
        return trip

    def sync_to_firestore(self, user_id: str = "default") -> bool:
        """Optionally pushes local SQLite trips and profile state to Cloud Firestore if FIREBASE_PROJECT_ID is set."""
        try:
            url = firestore_doc_url(user_id)
            if not url:
                return False
            clean_user = sanitize_user_id(user_id)
            trips = self.list_trips()
            payload = {
                "userId": clean_user,
                "trips": [
                    {
                        "destination": t["destination"],
                        "country": t["country"],
                        "startDate": t["start_date"],
                        "durationDays": t["duration_days"],
                        "avgHighF": t["weather"].get("avg_high_f", 64.0),
                        "avgLowF": t["weather"].get("avg_low_f", 49.0),
                        "rainyDaysCount": t["weather"].get("rainy_days_count", 1),
                        "plugTypes": t["weather"].get("plug_types", "Type A/B"),
                        "tripMode": t["trip_mode"],
                        "tagsCsv": ",".join(t["tags"]),
                        "lastAgentNote": f"Synced from PackTrack ({clean_user})",
                        "items": [
                            {
                                "name": i["name"],
                                "category": i["category"],
                                "quantity": i["quantity"],
                                "bagLocation": i["bag_location"],
                                "packedOutbound": i["packed_outbound"],
                                "packedReturn": i["packed_return"],
                                "isDoorwayCritical": i["is_doorway_critical"],
                                "isHighLossReturn": i["is_high_loss_return"],
                                "weatherReason": i["weather_reason"],
                            }
                            for i in t["items"]
                        ],
                    }
                    for t in trips
                ],
                "custom_rules": [],
            }
            doc_body = {
                "fields": {
                    "userId": {"stringValue": clean_user},
                    "payload": {"stringValue": json.dumps(payload)},
                    "tripCount": {"integerValue": str(len(trips))},
                    "updatedAt": {"stringValue": datetime.utcnow().isoformat() + "Z"},
                }
            }
            req = urllib.request.Request(
                url,
                data=json.dumps(doc_body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="PATCH",
            )
            with urllib.request.urlopen(req, timeout=5.0) as resp:
                return 200 <= resp.status < 300
        except Exception:
            return False
