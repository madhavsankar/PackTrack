"""HTTP & REST Server for PackTrack Companion Mobile UI and Remote Agent Webhook/MCP."""

import json
import os
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Dict, Any
from .db import PackTrackDB
from .weather import fetch_destination_weather
from .engine import generate_gap_questions, build_complete_checklist
from .mcp_server import MCP_TOOLS, execute_mcp_tool
from .gemini_agent import GeminiVoiceAgent


db = PackTrackDB()
voice_agent = GeminiVoiceAgent()
STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")


def seed_sample_trip_if_empty():
    trips = db.list_trips()
    if not trips:
        profile = db.get_profile()
        weather = fetch_destination_weather("London", duration_days=5, home_country_code="US")
        items = build_complete_checklist(
            trip_id=0,
            weather=weather,
            profile=profile,
            selected_tags=["work", "international", "gym"],
            do_laundry=False,
            rain_preference="Rain jacket + Compact umbrella",
        )
        db.create_trip(weather=weather, tags=["work", "international", "gym"], items=items)


class PackTrackHTTPHandler(BaseHTTPRequestHandler):
    def _send_json(self, data: Any, status: int = 200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> Dict[str, Any]:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_OPTIONS(self):
        self._send_json({"ok": True})

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path in ("/", "/index.html"):
            index_path = os.path.join(STATIC_DIR, "index.html")
            with open(index_path, "rb") as f:
                content = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        if path == "/api/profile":
            self._send_json(db.get_profile())
            return

        if path == "/api/trips":
            self._send_json({"trips": db.list_trips()})
            return

        if path.startswith("/api/trips/"):
            try:
                trip_id = int(path.split("/")[-1])
                trip = db.get_trip(trip_id)
                if not trip:
                    self._send_json({"error": "Trip not found"}, 404)
                else:
                    self._send_json(trip)
            except ValueError:
                self._send_json({"error": "Invalid trip ID"}, 400)
            return

        if path == "/api/mcp/tools":
            self._send_json({"tools": MCP_TOOLS})
            return

        self._send_json({"error": "Not found"}, 404)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        body = self._read_json()

        if path == "/api/analyze":
            destination = body.get("destination", "Tokyo")
            start_date = body.get("start_date")
            duration_days = int(body.get("duration_days", 5))
            initial_tags = body.get("initial_tags", [])
            profile = db.get_profile()
            weather = fetch_destination_weather(
                destination=destination,
                start_date_str=start_date,
                duration_days=duration_days,
                home_country_code=profile.get("home_country_code", "US"),
            )
            questions = generate_gap_questions(weather, profile, initial_tags)
            self._send_json({
                "weather": weather.to_dict(),
                "questions": questions,
            })
            return

        if path == "/api/trips":
            destination = body.get("destination", "Tokyo")
            start_date = body.get("start_date")
            duration_days = int(body.get("duration_days", 5))
            selected_tags = body.get("selected_tags", [])
            do_laundry = bool(body.get("do_laundry", False))
            rain_preference = body.get("rain_preference", "Rain jacket + Compact umbrella")
            custom_items = [
                {"name": name, "category": "Basics (Leave-the-House)"}
                for name in body.get("custom_items", [])
                if name.strip()
            ]
            profile = db.get_profile()
            weather = fetch_destination_weather(
                destination=destination,
                start_date_str=start_date,
                duration_days=duration_days,
                home_country_code=profile.get("home_country_code", "US"),
            )
            items = build_complete_checklist(
                trip_id=0,
                weather=weather,
                profile=profile,
                selected_tags=selected_tags,
                do_laundry=do_laundry,
                rain_preference=rain_preference,
                custom_extra_items=custom_items,
            )
            trip = db.create_trip(weather=weather, tags=selected_tags, items=items)
            self._send_json(trip, 201)
            return

        if path == "/api/items/toggle":
            item_id = int(body["item_id"])
            phase = body.get("phase", "outbound")
            checked = body.get("checked")
            trip = db.toggle_item(item_id=item_id, phase=phase, checked=checked)
            self._send_json(trip)
            return

        if path == "/api/items/add":
            trip_id = int(body["trip_id"])
            name = body["name"]
            category = body.get("category", "Basics (Leave-the-House)")
            quantity = int(body.get("quantity", 1))
            is_doorway_critical = bool(body.get("is_doorway_critical", False))
            trip = db.add_custom_item_to_trip(trip_id, name, category, quantity, is_doorway_critical)
            self._send_json(trip)
            return

        if path == "/api/trips/mode":
            trip_id = int(body["trip_id"])
            mode = body.get("mode", "OUTBOUND")
            trip = db.set_trip_mode(trip_id, mode)
            self._send_json(trip)
            return

        if path == "/api/template/add":
            updated = db.add_template_item(
                module_or_section=body.get("module", "basics"),
                item_name=body["item_name"],
                category=body.get("category", "Basics (Leave-the-House)"),
                is_doorway_critical=bool(body.get("is_doorway_critical", False)),
            )
            self._send_json(updated)
            return

        if path == "/api/mcp/call":
            tool_name = body["name"]
            tool_args = body.get("arguments", {})
            res = execute_mcp_tool(tool_name, tool_args)
            self._send_json(res)
            return

        if path == "/api/voice-chat":
            user_text = body.get("text", "")
            session_id = body.get("session_id", "default")
            active_trip_id = body.get("active_trip_id")
            api_key = body.get("api_key")
            res = voice_agent.handle_turn(
                user_text=user_text,
                session_id=session_id,
                active_trip_id=active_trip_id,
                api_key=api_key,
            )
            self._send_json(res)
            return

        self._send_json({"error": "Not found"}, 404)

    def log_message(self, fmt, *args):
        return


def run_http_server(port: int = 8090):
    seed_sample_trip_if_empty()
    server = HTTPServer(("0.0.0.0", port), PackTrackHTTPHandler)
    print(f"PackTrack Companion Server & API running at http://localhost:{port}")
    server.serve_forever()


if __name__ == "__main__":
    run_http_server()

