/**
 * HTTP & REST API Controller for PackTrack Cloudflare Worker.
 */

import { FirestoreRepository } from "../repositories/FirestoreRepository.js";
import { PackingEngineService } from "../services/PackingEngineService.js";
import { McpRouter } from "../mcp/McpRouter.js";
import { WEB_APP_HTML } from "../ui/WebAppView.js";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-User-Id, X-Google-Id-Token",
};

const VERIFIED_GOOGLE_TOKENS = new Map();
const SESSION_MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000; // 90 days
let GOOGLE_JWKS_CACHE = { keys: null, fetchedAt: 0 };

function decodeBase64UrlBytes(b64url) {
  const b64 = String(b64url || "")
    .replace(/-/g, "+")
    .replace(/_/g, "/");
  const padded = b64 + "===".slice((b64.length + 3) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

function decodeBase64UrlJson(b64url) {
  const bytes = decodeBase64UrlBytes(b64url);
  return JSON.parse(new TextDecoder().decode(bytes));
}

async function verifyGoogleJwtWithJwks(token, expectedAud) {
  const parts = String(token || "").split(".");
  if (parts.length !== 3) return null;
  const header = decodeBase64UrlJson(parts[0]);
  const payload = decodeBase64UrlJson(parts[1]);
  if (!header || header.alg !== "RS256" || !header.kid || !payload) return null;

  const iss = String(payload.iss || "");
  if (iss !== "https://accounts.google.com" && iss !== "accounts.google.com") {
    return null;
  }
  if (expectedAud && payload.aud !== expectedAud) {
    return { error: "Invalid Google OAuth client audience." };
  }
  if (!payload.email || String(payload.email_verified) !== "true") {
    return { error: "Google account email is not verified." };
  }

  const iatSec = Number(payload.iat || payload.exp || 0);
  const sessionExpMs = iatSec > 0 ? iatSec * 1000 + SESSION_MAX_AGE_MS : Date.now() + SESSION_MAX_AGE_MS;
  if (sessionExpMs <= Date.now()) {
    return { error: "Google session expired. Please sign in again." };
  }

  const now = Date.now();
  if (!GOOGLE_JWKS_CACHE.keys || now - GOOGLE_JWKS_CACHE.fetchedAt > 6 * 3600 * 1000) {
    const res = await fetch("https://www.googleapis.com/oauth2/v3/certs");
    if (res.ok) {
      const data = await res.json();
      if (Array.isArray(data.keys)) {
        GOOGLE_JWKS_CACHE = { keys: data.keys, fetchedAt: now };
      }
    }
  }
  const jwk = (GOOGLE_JWKS_CACHE.keys || []).find((k) => k.kid === header.kid);
  if (!jwk) return null;

  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );
  const signedData = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const sigBytes = decodeBase64UrlBytes(parts[2]);
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", cryptoKey, sigBytes, signedData);
  if (!valid) return null;

  return {
    email: String(payload.email).trim().toLowerCase(),
    expMs: sessionExpMs,
  };
}

export class HttpRouter {
  constructor(env) {
    this.env = env;
    this.repo = new FirestoreRepository(env);
    this.mcp = new McpRouter(this.repo);
  }

  _getGoogleClientId() {
    return String(this.env?.GOOGLE_CLIENT_ID || "").trim();
  }

  _isEmailAllowed(email) {
    const cleanEmail = String(email || "").trim().toLowerCase();
    if (!cleanEmail) return false;
    const allowedRaw = String(this.env?.ALLOWED_EMAILS || "").trim();
    if (!allowedRaw) return false;
    const allowedSet = new Set(
      allowedRaw
        .split(",")
        .flatMap((entry) => {
          const clean = entry.trim().toLowerCase();
          if (!clean) return [];
          return [clean, clean.split("@")[0], FirestoreRepository.sanitizeUserId(clean)];
        })
    );
    return allowedSet.has(cleanEmail) || allowedSet.has(cleanEmail.split("@")[0]);
  }

  async _verifyGoogleRequest(request, url) {
    const cfAccessEmail = (
      request.headers.get("Cf-Access-Authenticated-User-Email") || ""
    )
      .trim()
      .toLowerCase();
    if (cfAccessEmail && this._isEmailAllowed(cfAccessEmail)) {
      return { ok: true, email: cfAccessEmail };
    }

    const authHeader = request.headers.get("Authorization") || "";
    const bearerToken = authHeader.startsWith("Bearer ")
      ? authHeader.slice(7).trim()
      : "";
    const token =
      bearerToken ||
      (request.headers.get("X-Google-Id-Token") || "").trim() ||
      (url.searchParams.get("id_token") || "").trim();

    if (!token) {
      return {
        ok: false,
        status: 401,
        error: "Please sign in with your Google account.",
      };
    }

    let verifiedEmail = "";
    const cached = VERIFIED_GOOGLE_TOKENS.get(token);
    if (cached && cached.expMs > Date.now()) {
      verifiedEmail = cached.email;
    } else {
      const expectedAud = this._getGoogleClientId();
      try {
        // 1. Cryptographically verify Google RS256 JWT against Google JWKS with 90-day session lifetime
        const jwksResult = await verifyGoogleJwtWithJwks(token, expectedAud);
        if (jwksResult && jwksResult.error) {
          return { ok: false, status: 401, error: jwksResult.error };
        }
        if (jwksResult && jwksResult.email) {
          verifiedEmail = jwksResult.email;
          VERIFIED_GOOGLE_TOKENS.set(token, {
            email: verifiedEmail,
            expMs: jwksResult.expMs,
          });
        }
      } catch (_) {}

      // 2. Fallback to oauth2.googleapis.com/tokeninfo if JWKS key rolled or wasn't matched
      if (!verifiedEmail) {
        try {
          const res = await fetch(
            `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(token)}`
          );
          if (!res.ok) {
            return {
              ok: false,
              status: 401,
              error: "Google session expired or invalid. Please sign in again.",
            };
          }
          const info = await res.json();
          if (expectedAud && info.aud !== expectedAud) {
            return {
              ok: false,
              status: 401,
              error: "Invalid Google OAuth client audience.",
            };
          }
          if (String(info.email_verified) !== "true" || !info.email) {
            return {
              ok: false,
              status: 401,
              error: "Google account email is not verified.",
            };
          }
          const iatMs = Number(info.iat || info.exp || 0) * 1000;
          const sessionExpMs = iatMs > 0 ? iatMs + SESSION_MAX_AGE_MS : Date.now() + SESSION_MAX_AGE_MS;
          verifiedEmail = String(info.email).trim().toLowerCase();
          VERIFIED_GOOGLE_TOKENS.set(token, { email: verifiedEmail, expMs: sessionExpMs });
        } catch (_) {
          return {
            ok: false,
            status: 401,
            error: "Unable to verify Google token.",
          };
        }
      }
    }

    if (!this._isEmailAllowed(verifiedEmail)) {
      return {
        ok: false,
        status: 403,
        error: `Access denied for ${verifiedEmail}. Only allowlisted Google accounts are permitted.`,
      };
    }

    return { ok: true, email: verifiedEmail };
  }

  json(data, status = 200) {
    return new Response(JSON.stringify(data), {
      status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        ...CORS_HEADERS,
      },
    });
  }

  async handle(request) {
    if (request.method === "OPTIONS") {
      return this.json({ ok: true });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/api/auth-config") {
      return this.json({
        googleClientId: this._getGoogleClientId(),
      });
    }

    let userParam = "default";
    if ((path.startsWith("/api/") && path !== "/api/health") || path === "/mcp") {
      const auth = await this._verifyGoogleRequest(request, url);
      if (!auth.ok) {
        return this.json(
          { error: auth.error, authRequired: true },
          auth.status || 401
        );
      }
      userParam = auth.email;
    }

    if (request.method === "GET") {
      if (path === "/" || path === "/index.html") {
        return new Response(WEB_APP_HTML, {
          status: 200,
          headers: {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-cache, no-store, must-revalidate",
            ...CORS_HEADERS,
          },
        });
      }

      if (path === "/manifest.json") {
        return this.json({
          name: "PackTrack",
          short_name: "PackTrack",
          start_url: "/",
          display: "standalone",
          background_color: "#0b0f17",
          theme_color: "#0b0f17",
        });
      }

      if (path === "/api/health") {
        return this.json({ status: "ok", service: "pack-track" });
      }

      if (path === "/api/state") {
        const state = await this.repo.getUserState(userParam);
        return this.json(state);
      }
    }

    if (request.method === "POST") {
      if (path === "/mcp") {
        return this.mcp.handle(request, userParam);
      }

      const body = await request.json().catch(() => ({}));
      const userId = userParam;

      if (path === "/api/state") {
        const state = {
          userId: FirestoreRepository.sanitizeUserId(userId),
          email: body.email || FirestoreRepository.displayUserLabel(userId),
          trips: Array.isArray(body.trips) ? body.trips : [],
          custom_rules: Array.isArray(body.custom_rules) ? body.custom_rules : [],
        };
        await this.repo.saveUserState(userId, state);
        return this.json(state);
      }

      if (path === "/api/trips/create") {
        const state = await this.repo.getUserState(userId);
        const newTrip = await PackingEngineService.createTripObject({
          destination: body.destination || "Seattle",
          durationDays: body.durationDays || 5,
          startDate: body.startDate || "",
          endDate: body.endDate || "",
          tags: body.tags || ["work", "rental_car", "carry_on"],
          customRules: state.custom_rules,
          geminiApiKey: body.geminiApiKey || this.env?.GEMINI_API_KEY || "",
        });
        state.trips.unshift(newTrip);
        await this.repo.saveUserState(userId, state);
        return this.json(state, 201);
      }

      if (path === "/api/trips/recreate") {
        const state = await this.repo.getUserState(userId);
        const idx = Number(body.tripIndex) || 0;
        const existing = state.trips?.[idx];
        if (!existing) {
          return this.json({ error: "Trip not found" }, 404);
        }
        const prevMap = new Map();
        for (const it of existing.items || []) {
          if (!String(it.weatherReason || "").startsWith("[SUGGESTED] ")) {
            prevMap.set(String(it.name || "").trim().toLowerCase(), it);
          }
        }
        const tags = String(existing.tagsCsv || "work,rental_car,carry_on")
          .split(",")
          .map((t) => t.trim())
          .filter(Boolean);
        const recreated = await PackingEngineService.createTripObject({
          destination: existing.destination || "Atlanta",
          durationDays: existing.durationDays || 3,
          startDate: existing.startDate || "",
          tags,
          customRules: state.custom_rules,
          geminiApiKey: body.geminiApiKey || this.env?.GEMINI_API_KEY || "",
        });
        recreated.tripMode = existing.tripMode || "OUTBOUND";
        recreated.items = (recreated.items || []).map((item) => {
          const prev = prevMap.get(String(item.name || "").trim().toLowerCase());
          if (prev) {
            return {
              ...item,
              packedOutbound: Boolean(prev.packedOutbound),
              packedReturn: Boolean(prev.packedReturn),
            };
          }
          return item;
        });
        state.trips[idx] = recreated;
        await this.repo.saveUserState(userId, state);
        return this.json(state, 200);
      }

      if (path === "/api/command") {
        const state = await this.repo.getUserState(userId);
        const res = await PackingEngineService.handleNaturalLanguageCommand(
          body.text || "",
          state,
          body.activeTripIndex || 0
        );
        await this.repo.saveUserState(userId, res.state);
        return this.json(res);
      }
    }

    return this.json({ error: "Not found" }, 404);
  }
}

