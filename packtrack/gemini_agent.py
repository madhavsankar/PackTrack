"""Gemini 3.8 Flash Voice & Conversational Agent for PackTrack.

Uses the current Gemini Interactions API (`v1beta/interactions` with `gemini-3.8-flash`
and stateful `previous_interaction_id` + `tools` declarations) to:
  1. Understand natural voice/text ("Going to London for 5 days on Tuesday for work")
  2. Call `analyze_trip_and_ask_questions` (fetches live weather & template gap questions)
  3. Interview the user naturally
  4. Call `generate_trip_checklist` or `update_packing_status` to directly update the SQLite DB!

Also includes a smart local fallback parser if no GEMINI_API_KEY is configured yet,
so the Voice Mic button works out-of-the-box at $0 cost.
"""

import json
import os
import re
import urllib.request
from typing import Dict, Any, List, Optional
from .mcp_server import MCP_TOOLS, execute_mcp_tool
from .weather import KNOWN_CITIES


GEMINI_MODEL = "gemini-3.8-flash"
INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

SYSTEM_INSTRUCTION = (
    "You are PackTrack, a concise, voice-friendly travel packing assistant integrated directly "
    "into the user's mobile checklist app. "
    "Workflow:\n"
    "1. When the user mentions a new trip (destination and/or duration), call `analyze_trip_and_ask_questions`.\n"
    "2. Briefly summarize the destination's weather forecast (temperatures & rain) in 1 sentence and ask 2-3 "
    "key clarifying questions from the tool output (e.g. work vs leisure, rain gear, gym/formal activities).\n"
    "3. Once the user answers (or if they already gave enough details like 'work + gym, create the list now'), "
    "call `generate_trip_checklist` so the categorized items (Basics, Documents, Office/Work, Clothes, Tech, Toiletries) "
    "are saved directly to their app database.\n"
    "4. If the user asks to check off items ('mark keys, passport, and charger packed') or switch modes "
    "('switch to doorway check' or 'start hotel checkout sweep'), call `update_packing_status`.\n"
    "Keep responses short (2-3 sentences max) so they sound natural when spoken aloud."
)


def _get_gemini_tools() -> List[Dict[str, Any]]:
    """Converts MCP tool schemas into Gemini Interactions API function declarations."""
    declarations = []
    for t in MCP_TOOLS:
        declarations.append({
            "type": "function",
            "name": t["name"],
            "description": t["description"],
            "parameters": t["inputSchema"],
        })
    return declarations


class GeminiVoiceAgent:
    """Manages multi-turn voice/chat sessions with Gemini 3.8 Flash Interactions API."""

    def __init__(self):
        self.sessions: Dict[str, str] = {}  # session_id -> previous_interaction_id
        self.pending_trip_context: Dict[str, Dict[str, Any]] = {}

    def handle_turn(
        self,
        user_text: str,
        session_id: str = "default",
        active_trip_id: Optional[int] = None,
        api_key: Optional[str] = None,
    ) -> Dict[str, Any]:
        key = (api_key or os.environ.get("GEMINI_API_KEY", "")).strip()
        if key:
            try:
                return self._call_gemini_interactions_api(user_text, session_id, active_trip_id, key)
            except Exception as exc:
                # Fall back gracefully if key is invalid or offline
                fallback = self._local_smart_agent(user_text, session_id, active_trip_id)
                fallback["note"] = f"(Used local agent fallback: {exc})"
                return fallback
        return self._local_smart_agent(user_text, session_id, active_trip_id)

    def _call_gemini_interactions_api(
        self,
        user_text: str,
        session_id: str,
        active_trip_id: Optional[int],
        api_key: str,
    ) -> Dict[str, Any]:
        tools = _get_gemini_tools()
        prev_id = self.sessions.get(session_id)

        context_prefix = f"[Active Trip ID in App: {active_trip_id}] " if active_trip_id else ""
        payload: Dict[str, Any] = {
            "model": GEMINI_MODEL,
            "system_instruction": SYSTEM_INSTRUCTION,
            "input": context_prefix + user_text,
            "tools": tools,
        }
        if prev_id:
            payload["previous_interaction_id"] = prev_id

        interaction = self._post_interaction(payload, api_key)
        self.sessions[session_id] = interaction.get("id", prev_id)

        executed_tools: List[Dict[str, Any]] = []
        updated_trip: Optional[Dict[str, Any]] = None

        # Loop up to 3 tool-call rounds if Gemini chains function calls
        for _ in range(3):
            steps = interaction.get("steps", [])
            fc_steps = [s for s in steps if s.get("type") == "function_call"]
            if not fc_steps:
                break

            result_steps = []
            for fc in fc_steps:
                fn_name = fc.get("name", "")
                fn_args = fc.get("arguments", {}) or {}
                if fn_name == "update_packing_status" and "trip_id" not in fn_args and active_trip_id:
                    fn_args["trip_id"] = active_trip_id

                tool_output = execute_mcp_tool(fn_name, fn_args)
                executed_tools.append({"tool": fn_name, "arguments": fn_args})
                if isinstance(tool_output, dict) and "trip" in tool_output:
                    updated_trip = tool_output["trip"]

                result_steps.append({
                    "type": "function_result",
                    "name": fn_name,
                    "call_id": fc.get("id"),
                    "result": [{"type": "text", "text": json.dumps(tool_output)}],
                })

            followup_payload: Dict[str, Any] = {
                "model": GEMINI_MODEL,
                "system_instruction": SYSTEM_INSTRUCTION,
                "previous_interaction_id": interaction["id"],
                "input": result_steps,
                "tools": tools,
            }
            interaction = self._post_interaction(followup_payload, api_key)
            self.sessions[session_id] = interaction.get("id", self.sessions.get(session_id))

        reply_text = self._extract_output_text(interaction)
        return {
            "reply": reply_text,
            "executed_tools": executed_tools,
            "updated_trip": updated_trip,
            "model": GEMINI_MODEL,
        }

    def _post_interaction(self, payload: Dict[str, Any], api_key: str) -> Dict[str, Any]:
        req = urllib.request.Request(
            INTERACTIONS_ENDPOINT,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "x-goog-api-key": api_key,
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=15.0) as resp:
            return json.loads(resp.read().decode("utf-8"))

    @staticmethod
    def _extract_output_text(interaction: Dict[str, Any]) -> str:
        if interaction.get("output_text"):
            return interaction["output_text"]
        texts = []
        for step in interaction.get("steps", []):
            if step.get("type") == "model_output":
                for part in step.get("content", []):
                    if part.get("type") == "text" and part.get("text"):
                        texts.append(part["text"])
        return "\n".join(texts) or "Done! Your PackTrack checklist is updated."

    def _local_smart_agent(
        self,
        user_text: str,
        session_id: str,
        active_trip_id: Optional[int],
    ) -> Dict[str, Any]:
        """Offline/Zero-Key conversational parser that calls the exact same MCP tools."""
        text_lower = user_text.lower()
        executed_tools = []
        updated_trip = None

        # Case 1: Mode switching by voice ("doorway check", "hotel checkout", "return mode")
        if active_trip_id and any(k in text_lower for k in ("doorway", "leave the house", "hotel checkout", "return sweep", "repack")):
            mode = "DOORWAY" if ("doorway" in text_lower or "leave" in text_lower) else "RETURN_SWEEP"
            res = execute_mcp_tool("update_packing_status", {"trip_id": active_trip_id, "trip_mode": mode})
            updated_trip = res.get("trip")
            executed_tools.append({"tool": "update_packing_status", "arguments": {"trip_id": active_trip_id, "trip_mode": mode}})
            label = "Doorway Check (Must-Have Departure Items)" if mode == "DOORWAY" else "Hotel Checkout Sweep (High-loss chargers & safe items first)"
            return {
                "reply": f"Switched your app to {label}. Ready for you to check off!",
                "executed_tools": executed_tools,
                "updated_trip": updated_trip,
                "model": "packtrack-local-voice-engine",
            }

        # Case 2: Checking off items by voice ("mark keys, passport and charger packed")
        if active_trip_id and any(k in text_lower for k in ("pack ", "packed", "check off", "mark ")):
            candidates = [
                "keys", "cash", "license", "passport", "badge", "charger", "power bank", "headphones",
                "packing cubes", "sunglasses", "shoes", "slippers", "belt", "jeans",
                "shorts", "t-shirts", "tshirts", "socks", "underwear", "thermals", "jacket", "raincoat",
                "cable", "insurance", "first-aid", "sunscreen", "toothbrush", "work device"
            ]
            matched = [c for c in candidates if c in text_lower]
            if matched:
                phase = "return" if "return" in text_lower or "hotel" in text_lower else "outbound"
                res = execute_mcp_tool(
                    "update_packing_status",
                    {"trip_id": active_trip_id, "item_names": matched, "phase": phase, "checked": True},
                )
                updated_trip = res.get("trip")
                executed_tools.append({"tool": "update_packing_status", "arguments": {"item_names": matched}})
                return {
                    "reply": f"Checked off {', '.join(matched)} in your checklist!",
                    "executed_tools": executed_tools,
                    "updated_trip": updated_trip,
                    "model": "packtrack-local-voice-engine",
                }

        # Case 3: Answering pending clarifying questions to finalize a trip
        if session_id in self.pending_trip_context and not any(city in text_lower for city in KNOWN_CITIES):
            ctx = self.pending_trip_context.pop(session_id)
            tags = list(ctx.get("tags", []))
            for tag_kw, tag_val in [
                ("work", "work"), ("office", "work"), ("business", "work"),
                ("own car", "own_car"), ("own vehicle", "own_car"), ("driving", "own_car"), ("insurance", "own_car"),
                ("rental", "rental_car"),
                ("gym", "gym"), ("workout", "gym")
            ]:
                if tag_kw in text_lower and tag_val not in tags:
                    tags.append(tag_val)
            do_laundry = "laundry" in text_lower and "no laundry" not in text_lower
            res = execute_mcp_tool(
                "generate_trip_checklist",
                {
                    "destination": ctx["destination"],
                    "duration_days": ctx["duration_days"],
                    "selected_tags": tags,
                    "do_laundry": do_laundry,
                },
            )
            updated_trip = res.get("trip")
            executed_tools.append({"tool": "generate_trip_checklist", "arguments": {"destination": ctx["destination"], "selected_tags": tags}})
            return {
                "reply": (
                    f"Created your {ctx['duration_days']}-day {ctx['destination']} checklist with "
                    f"{len(updated_trip['items'])} categorized items ({', '.join(tags) or 'rental_car, work'}). "
                    f"It's now open in the app below!"
                ),
                "executed_tools": executed_tools,
                "updated_trip": updated_trip,
                "model": "packtrack-local-voice-engine",
            }

        # Case 4: Starting a new trip ("Going to Tokyo for 6 days for work")
        detected_city = None
        for city in KNOWN_CITIES:
            if city in text_lower:
                detected_city = city.title()
                break
        if not detected_city:
            m_city = re.search(r"to\s+([A-Za-z\s]{3,20}?)(?:\s+for|\s+on|\s+next|$)", user_text, re.IGNORECASE)
            if m_city:
                detected_city = m_city.group(1).strip().title()

        days_match = re.search(r"(\d+)\s*(?:day|night)", text_lower)
        duration_days = int(days_match.group(1)) if days_match else 5

        initial_tags = []
        for kw, tag in [
            ("work", "work"), ("office", "work"), ("business", "work"),
            ("own car", "own_car"), ("own vehicle", "own_car"), ("driving", "own_car"),
            ("rental", "rental_car"),
            ("gym", "gym"), ("workout", "gym")
        ]:
            if kw in text_lower and tag not in initial_tags:
                initial_tags.append(tag)

        if detected_city:
            analysis = execute_mcp_tool(
                "analyze_trip_and_ask_questions",
                {"destination": detected_city, "duration_days": duration_days, "initial_tags": initial_tags},
            )
            executed_tools.append({"tool": "analyze_trip_and_ask_questions", "arguments": {"destination": detected_city, "duration_days": duration_days}})
            w = analysis["weather_summary"]
            if w.get("is_international") and "international" not in initial_tags:
                initial_tags.append("international")

            # If user explicitly said "create/generate now" or gave 2+ tags, create immediately; otherwise ask Q&A!
            if "generate" in text_lower or "create list" in text_lower or len(initial_tags) >= 2:
                res = execute_mcp_tool(
                    "generate_trip_checklist",
                    {
                        "destination": detected_city,
                        "duration_days": duration_days,
                        "selected_tags": initial_tags or ["work"],
                        "do_laundry": "laundry" in text_lower,
                    },
                )
                updated_trip = res.get("trip")
                executed_tools.append({"tool": "generate_trip_checklist", "arguments": {"destination": detected_city, "selected_tags": initial_tags}})
                return {
                    "reply": (
                        f"{w['destination_name']} will be {w['avg_low_f']}°F–{w['avg_high_f']}°F with "
                        f"{w['rainy_days_count']} rainy days. I've generated your {duration_days}-day checklist "
                        f"with Basics, Documents, {', '.join(initial_tags)}, and weather clothes!"
                    ),
                    "executed_tools": executed_tools,
                    "updated_trip": updated_trip,
                    "model": "packtrack-local-voice-engine",
                }

            self.pending_trip_context[session_id] = {
                "destination": detected_city,
                "duration_days": duration_days,
                "tags": initial_tags,
            }
            questions = [q["question"] for q in analysis["clarifying_questions"][:2]]
            q_str = " ".join(questions)
            return {
                "reply": (
                    f"{w['destination_name']} forecast is {w['avg_low_f']}°F–{w['avg_high_f']}°F "
                    f"({w['rainy_days_count']} rainy days, plug: {w['plug_types']}). Before I build your list: {q_str}"
                ),
                "executed_tools": executed_tools,
                "updated_trip": None,
                "model": "packtrack-local-voice-engine",
            }

        return {
            "reply": (
                "Tell me where and how long you're traveling (for example: 'Going to Tokyo for 6 days for work and gym'), "
                "or say 'Mark keys, passport, and charger as packed'!"
            ),
            "executed_tools": [],
            "updated_trip": None,
            "model": "packtrack-local-voice-engine",
        }

