/**
 * Streamable HTTP MCP Router (`/mcp`) for PackTrack Cloudflare Worker.
 */

import { PackingEngineService } from "../services/PackingEngineService.js";

export const MCP_TOOLS = [
  {
    name: "list_trips",
    description: "List all trips and packing progress for a user profile.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string", description: "User email or ID" },
      },
    },
  },
  {
    name: "create_trip",
    description: "Create a weather-aware packing checklist for a destination.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string", description: "User email or ID" },
        destination: { type: "string", description: "City or country name" },
        duration_days: { type: "integer", description: "Trip length in days" },
        tags: {
          type: "array",
          items: { type: "string" },
          description: "Trip context tags (e.g. work, rental_car, carry_on, checked_bag)",
        },
      },
      required: ["destination"],
    },
  },
  {
    name: "toggle_item",
    description: "Check or uncheck a packing item by name on the active trip.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string" },
        trip_index: { type: "integer", default: 0 },
        item_name: { type: "string" },
        checked: { type: "boolean", default: true },
        phase: { type: "string", enum: ["outbound", "return"], default: "outbound" },
      },
      required: ["item_name"],
    },
  },
  {
    name: "set_trip_mode",
    description: "Switch trip mode between OUTBOUND, DOORWAY, and RETURN_SWEEP.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string" },
        trip_index: { type: "integer", default: 0 },
        mode: { type: "string", enum: ["OUTBOUND", "DOORWAY", "RETURN_SWEEP"] },
      },
      required: ["mode"],
    },
  },
];

export class McpRouter {
  constructor(repo, env = null) {
    this.repo = repo;
    this.env = env;
  }

  async handle(request, defaultUser = "default") {
    const body = await request.json().catch(() => ({}));
    const { id = null, method, params = {} } = body;

    if (method === "initialize") {
      return Response.json({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: "2024-11-05",
          serverInfo: { name: "packtrack-mcp", version: "1.0.0" },
          capabilities: { tools: {} },
        },
      });
    }

    if (method === "tools/list") {
      return Response.json({
        jsonrpc: "2.0",
        id,
        result: { tools: MCP_TOOLS },
      });
    }

    if (method === "tools/call") {
      const name = params.name;
      const args = params.arguments || {};
      const userId = args.user || defaultUser;
      const state = await this.repo.getUserState(userId);
      let result = {};

      if (name === "list_trips") {
        result = { userId: state.userId, trips: state.trips, custom_rules_count: state.custom_rules.length };
      } else if (name === "create_trip") {
        const trip = await PackingEngineService.createTripObject({
          destination: args.destination || "Seattle",
          durationDays: args.duration_days || 5,
          tags: args.tags || ["work", "rental_car", "carry_on"],
          customRules: state.custom_rules,
          geminiApiKey: this.env?.GEMINI_API_KEY || "",
        });
        state.trips.unshift(trip);
        await this.repo.saveUserState(userId, state);
        result = trip;
      } else if (name === "toggle_item") {
        const idx = Number(args.trip_index) || 0;
        const trip = state.trips[idx];
        if (!trip) {
          result = { error: "Trip not found" };
        } else {
          const target = String(args.item_name || "").toLowerCase();
          const checked = args.checked !== false;
          const phase = args.phase === "return" ? "return" : "outbound";
          const matched = [];
          for (const item of trip.items || []) {
            if (String(item.name || "").toLowerCase().includes(target)) {
              if (phase === "return") item.packedReturn = checked;
              else item.packedOutbound = checked;
              matched.push(item.name);
            }
          }
          await this.repo.saveUserState(userId, state);
          result = { matched, trip };
        }
      } else if (name === "set_trip_mode") {
        const idx = Number(args.trip_index) || 0;
        const trip = state.trips[idx];
        if (trip) {
          trip.tripMode = args.mode || "OUTBOUND";
          await this.repo.saveUserState(userId, state);
        }
        result = { trip };
      } else {
        return Response.json({
          jsonrpc: "2.0",
          id,
          error: { code: -32601, message: `Unknown tool: ${name}` },
        });
      }

      return Response.json({
        jsonrpc: "2.0",
        id,
        result: {
          content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
        },
      });
    }

    return Response.json({
      jsonrpc: "2.0",
      id,
      error: { code: -32600, message: "Unsupported JSON-RPC method" },
    });
  }
}

