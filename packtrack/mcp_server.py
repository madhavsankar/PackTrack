"""Model Context Protocol (MCP) Server for PackTrack.

Exposes PackTrack's tools over JSON-RPC 2.0 (stdio) so Claude Desktop,
Claude Mobile (via remote MCP bridge), and Gemini agents can:
  1. Inspect & customize the user's Traveler Profile & Templates
  2. Analyze destination weather & get smart clarifying questions
  3. Generate a categorized packing checklist (Documents, Office/Work, Basics, Clothes, etc.)
  4. Check off items or switch between Doorway Check / Outbound / Hotel Checkout Sweep
"""

import json
import sys
from typing import Dict, Any, List
from .db import PackTrackDB
from .weather import fetch_destination_weather
from .engine import generate_gap_questions, build_complete_checklist


db = PackTrackDB()


MCP_TOOLS: List[Dict[str, Any]] = [
    {
        "name": "get_travel_profile",
        "description": (
            "Returns the user's PackTrack Traveler Profile & Templates, including Always-Pack Basics, "
            "Default Documents, and Modular Activity Templates "
            "(Office/Work, Own Vehicle, Rental Vehicle, International, Gym)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
        "annotations": {
            "readOnlyHint": True,
            "destructiveHint": False,
            "idempotentHint": True,
            "openWorldHint": False,
        },
    },
    {
        "name": "analyze_trip_and_ask_questions",
        "description": (
            "Stage 1 & 2 of PackTrack: Given a destination, start_date, and duration_days, fetches "
            "the weather forecast (high/low temps, rain/snow days, international status, plug adapters) "
            "and returns targeted clarifying questions to ask the traveler before generating the checklist."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "destination": {"type": "string", "description": "Destination city (e.g. 'London', 'Tokyo', 'Austin')"},
                "start_date": {"type": "string", "description": "Optional YYYY-MM-DD departure date"},
                "duration_days": {"type": "integer", "description": "Trip duration in days (e.g. 5)"},
                "initial_tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Any known tags from the prompt (e.g. ['work', 'gym'])",
                },
            },
            "required": ["destination", "duration_days"],
        },
        "annotations": {
            "readOnlyHint": True,
            "destructiveHint": False,
            "idempotentHint": True,
            "openWorldHint": True,
        },
    },
    {
        "name": "generate_trip_checklist",
        "description": (
            "Stage 3 of PackTrack: Creates and saves a complete, categorized packing checklist "
            "(Basics, Documents, Office/Work, Clothes with exact weather-adjusted counts, Tech, Toiletries) "
            "after the user answers the clarifying questions."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "destination": {"type": "string"},
                "start_date": {"type": "string"},
                "duration_days": {"type": "integer"},
                "selected_tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Selected activity modules: 'work', 'international', 'gym', 'formal', 'swim', 'hiking'",
                },
                "do_laundry": {"type": "boolean", "description": "True if user plans to do laundry mid-trip"},
                "rain_preference": {
                    "type": "string",
                    "description": "e.g., 'Rain jacket + Compact umbrella'",
                },
                "custom_items": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Optional extra item names requested by the user",
                },
            },
            "required": ["destination", "duration_days"],
        },
        "annotations": {
            "readOnlyHint": False,
            "destructiveHint": False,
            "idempotentHint": False,
            "openWorldHint": True,
        },
    },
    {
        "name": "update_packing_status",
        "description": (
            "Marks items as packed or unpacked (either for 'outbound' departure or 'return' hotel checkout sweep) "
            "and/or switches the active trip mode ('DOORWAY', 'OUTBOUND', 'RETURN_SWEEP')."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "trip_id": {"type": "integer"},
                "item_names": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of item names (or substrings like 'keys', 'passport') to mark",
                },
                "phase": {
                    "type": "string",
                    "enum": ["outbound", "return"],
                    "description": "'outbound' when packing for departure, 'return' when packing to leave hotel",
                },
                "checked": {"type": "boolean", "description": "True to mark packed, False to uncheck"},
                "trip_mode": {
                    "type": "string",
                    "enum": ["DOORWAY", "OUTBOUND", "RETURN_SWEEP"],
                    "description": "Optional mode switch for the mobile companion UI",
                },
            },
            "required": ["trip_id"],
        },
        "annotations": {
            "readOnlyHint": False,
            "destructiveHint": False,
            "idempotentHint": True,
            "openWorldHint": False,
        },
    },
    {
        "name": "customize_travel_template",
        "description": (
            "Permanently adds an item to the user's Traveler Template so future trips automatically include it "
            "(e.g., adding 'CPAP Machine' to 'basics' or 'Security Key' to 'work')."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "module_or_section": {
                    "type": "string",
                    "description": "'basics', 'documents', 'tech', 'work', 'international', 'gym', etc.",
                },
                "item_name": {"type": "string"},
                "category": {
                    "type": "string",
                    "description": "Category label such as 'Office / Work', 'Basics (Leave-the-House)', 'Documents'",
                },
                "is_doorway_critical": {"type": "boolean"},
            },
            "required": ["module_or_section", "item_name", "category"],
        },
        "annotations": {
            "readOnlyHint": False,
            "destructiveHint": False,
            "idempotentHint": True,
            "openWorldHint": False,
        },
    },
]


def execute_mcp_tool(name: str, args: Dict[str, Any]) -> Dict[str, Any]:
    if name == "get_travel_profile":
        return db.get_profile()

    if name == "analyze_trip_and_ask_questions":
        profile = db.get_profile()
        weather = fetch_destination_weather(
            destination=args["destination"],
            start_date_str=args.get("start_date"),
            duration_days=int(args.get("duration_days", 5)),
            home_country_code=profile.get("home_country_code", "US"),
        )
        questions = generate_gap_questions(
            weather=weather,
            profile=profile,
            initial_tags=args.get("initial_tags", []),
        )
        return {
            "weather_summary": weather.to_dict(),
            "clarifying_questions": questions,
            "agent_instruction": (
                "Present the weather forecast summary briefly to the user, ask the clarifying_questions "
                "concisely, and once answered, call generate_trip_checklist."
            ),
        }

    if name == "generate_trip_checklist":
        profile = db.get_profile()
        weather = fetch_destination_weather(
            destination=args["destination"],
            start_date_str=args.get("start_date"),
            duration_days=int(args.get("duration_days", 5)),
            home_country_code=profile.get("home_country_code", "US"),
        )
        custom_extras = [
            {"name": item_str, "category": "Basics (Leave-the-House)"}
            for item_str in args.get("custom_items", [])
        ]
        items = build_complete_checklist(
            trip_id=0,
            weather=weather,
            profile=profile,
            selected_tags=args.get("selected_tags", []),
            do_laundry=bool(args.get("do_laundry", False)),
            rain_preference=args.get("rain_preference", "Rain jacket + Compact umbrella"),
            custom_extra_items=custom_extras,
        )
        trip = db.create_trip(weather=weather, tags=args.get("selected_tags", []), items=items)
        return {
            "message": f"Created PackTrack checklist for {weather.destination_name} ({weather.duration_days} days).",
            "companion_ui_url": f"http://localhost:8090/?trip_id={trip['id']}",
            "trip": trip,
        }

    if name == "update_packing_status":
        trip_id = int(args["trip_id"])
        if args.get("trip_mode"):
            db.set_trip_mode(trip_id, args["trip_mode"])
        if args.get("item_names"):
            db.mark_items_by_name(
                trip_id=trip_id,
                item_names=args["item_names"],
                phase=args.get("phase", "outbound"),
                checked=bool(args.get("checked", True)),
            )
        return {"trip": db.get_trip(trip_id)}

    if name == "customize_travel_template":
        updated = db.add_template_item(
            module_or_section=args["module_or_section"],
            item_name=args["item_name"],
            category=args["category"],
            is_doorway_critical=bool(args.get("is_doorway_critical", False)),
        )
        return {"message": f"Added '{args['item_name']}' to template '{args['module_or_section']}'.", "profile": updated}

    raise ValueError(f"Unknown tool: {name}")


def run_stdio_mcp_server():
    """Standard JSON-RPC 2.0 stdio loop for MCP clients (Claude Desktop / Gemini CLI)."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue

        req_id = req.get("id")
        method = req.get("method", "")
        params = req.get("params", {})

        if method == "initialize":
            resp = {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "packtrack-mcp", "version": "1.0.0"},
                },
            }
        elif method == "tools/list":
            resp = {"jsonrpc": "2.0", "id": req_id, "result": {"tools": MCP_TOOLS}}
        elif method == "tools/call":
            tool_name = params.get("name", "")
            tool_args = params.get("arguments", {})
            try:
                result_data = execute_mcp_tool(tool_name, tool_args)
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": json.dumps(result_data, indent=2)}],
                        "isError": False,
                    },
                }
            except Exception as exc:
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [{"type": "text", "text": f"Error: {exc}"}],
                        "isError": True,
                    },
                }
        elif req_id is not None:
            resp = {"jsonrpc": "2.0", "id": req_id, "result": {}}
        else:
            continue

        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    run_stdio_mcp_server()

