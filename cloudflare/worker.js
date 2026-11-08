/**
 * PackTrack — Modular Cloudflare Worker Entrypoint.
 *
 * Architecture:
 *   - `cloudflare/src/repositories/FirestoreRepository.js` -> Multi-user Cloud Firestore (`packtrack_state/user_state_{userId}`)
 *   - `cloudflare/src/services/PackingEngineService.js`    -> Open-Meteo Weather & Smart Checklist Generator
 *   - `cloudflare/src/mcp/McpRouter.js`                    -> Streamable HTTP MCP Server (`/mcp`)
 *   - `cloudflare/src/controllers/HttpRouter.js`           -> HTTP & REST API controller
 *   - `cloudflare/src/ui/WebAppView.js`                    -> Minimalist Slate & Emerald Web App UI
 */

import { HttpRouter } from "./src/controllers/HttpRouter.js";

export default {
  async fetch(request, env) {
    const router = new HttpRouter(env);
    return router.handle(request);
  },
};

