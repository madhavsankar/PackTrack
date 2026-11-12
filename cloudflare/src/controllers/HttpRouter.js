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
const SESSION_MAX_AGE_MS = 3650 * 24 * 60 * 60 * 1000; // 10 years (3,650 days — until explicit Sign Out)
let GOOGLE_JWKS_CACHE = { keys: null, fetchedAt: 0 };

function encodeBase64UrlBytes(bytes) {
  let bin = "";
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < arr.length; i++) {
    bin += String.fromCharCode(arr[i]);
  }
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function encodeBase64UrlJson(obj) {
  return encodeBase64UrlBytes(new TextEncoder().encode(JSON.stringify(obj)));
}

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

async function getHmacCryptoKey(secret, usage) {
  const keyBytes = new TextEncoder().encode(String(secret || "packtrack-session-v1"));
  return crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    usage
  );
}

export async function mintWorkerSessionToken(email, secret) {
  const cleanEmail = String(email || "").trim().toLowerCase();
  const nowSec = Math.floor(Date.now() / 1000);
  const expSec = nowSec + 3650 * 24 * 60 * 60; // 10 years
  const headerB64 = encodeBase64UrlJson({ alg: "HS256", typ: "JWT" });
  const payloadB64 = encodeBase64UrlJson({
    iss: "worker-session",
    email: cleanEmail,
    email_verified: true,
    iat: nowSec,
    exp: expSec,
  });
  const signingInput = `${headerB64}.${payloadB64}`;
  const key = await getHmacCryptoKey(secret, ["sign"]);
  const sigBuf = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(signingInput)
  );
  return `${signingInput}.${encodeBase64UrlBytes(sigBuf)}`;
}

export async function verifyWorkerSessionToken(token, secret) {
  const parts = String(token || "").split(".");
  if (parts.length !== 3) return null;
  let header;
  let payload;
  try {
    header = decodeBase64UrlJson(parts[0]);
    payload = decodeBase64UrlJson(parts[1]);
  } catch (_) {
    return null;
  }
  if (!header || header.alg !== "HS256" || !payload || payload.iss !== "worker-session") {
    return null;
  }
  if (!payload.email) return null;
  const iatSec = Number(payload.iat || 0);
  const expMs = iatSec > 0 ? iatSec * 1000 + SESSION_MAX_AGE_MS : Number(payload.exp || 0) * 1000;
  if (expMs > 0 && expMs <= Date.now()) {
    return { error: "Session expired. Please sign in again." };
  }
  const key = await getHmacCryptoKey(secret, ["verify"]);
  const signedData = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const sigBytes = decodeBase64UrlBytes(parts[2]);
  const valid = await crypto.subtle.verify("HMAC", key, sigBytes, signedData);
  if (!valid) return null;
  return {
    email: String(payload.email).trim().toLowerCase(),
    expMs: expMs || Date.now() + SESSION_MAX_AGE_MS,
  };
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

  _getSessionSecret() {
    return (
      String(this.env?.SESSION_SECRET || "").trim() ||
      String(this.env?.FIREBASE_API_KEY || "").trim() ||
      String(this.env?.GEMINI_API_KEY || "").trim() ||
      String(this.env?.GOOGLE_CLIENT_ID || "").trim() ||
      "packtrack-session-v1"
    );
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
    let isWorkerSession = false;
    const sessionSecret = this._getSessionSecret();
    const cached = VERIFIED_GOOGLE_TOKENS.get(token);
    if (cached && cached.expMs > Date.now()) {
      verifiedEmail = cached.email;
      isWorkerSession = Boolean(cached.isWorkerSession);
    } else {
      try {
        // 0. Check 10-year Worker-signed HMAC-SHA256 session token first (unaffected by Google JWKS rotation)
        const workerRes = await verifyWorkerSessionToken(token, sessionSecret);
        if (workerRes && workerRes.error) {
          return { ok: false, status: 401, error: workerRes.error };
        }
        if (workerRes && workerRes.email) {
          verifiedEmail = workerRes.email;
          isWorkerSession = true;
          VERIFIED_GOOGLE_TOKENS.set(token, {
            email: verifiedEmail,
            expMs: workerRes.expMs,
            isWorkerSession: true,
          });
        }
      } catch (_) {}

      const expectedAud = this._getGoogleClientId();
      if (!verifiedEmail) {
        try {
          // 1. Cryptographically verify Google RS256 JWT against Google JWKS
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
      }

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

    try {
      this.activeSessionToken = isWorkerSession
        ? token
        : await mintWorkerSessionToken(verifiedEmail, sessionSecret);
    } catch (_) {
      this.activeSessionToken = token;
    }

    return { ok: true, email: verifiedEmail, sessionToken: this.activeSessionToken };
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
        return this.json({
          ...state,
          session_token: this.activeSessionToken || "",
        });
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
        const idx = Number.isInteger(Number(body.tripIndex)) ? Number(body.tripIndex) : 0;
        const existing = state.trips?.[idx] || state.trips?.[0];
        if (!existing) {
          return this.json({ error: "Trip not found" }, 404);
        }
        const targetIdx = state.trips?.[idx] ? idx : 0;
        const prevMap = new Map();
        const combinedExistingItems = [
          ...(existing.items || []),
          ...(Array.isArray(body.existingItems) ? body.existingItems : []),
        ];
        for (const it of combinedExistingItems) {
          if (!String(it?.weatherReason || "").startsWith("[SUGGESTED] ")) {
            const key = String(it?.name || "").trim().toLowerCase();
            if (!key) continue;
            const prev = prevMap.get(key);
            prevMap.set(key, {
              ...it,
              packedOutbound: Boolean(prev?.packedOutbound || it?.packedOutbound),
              packedReturn: Boolean(prev?.packedReturn || it?.packedReturn),
            });
          }
        }
        const tags =
          Array.isArray(body.tags) && body.tags.length > 0
            ? body.tags.map((t) => String(t).trim()).filter(Boolean)
            : String(existing.tagsCsv || "work,rental_car,carry_on")
                .split(",")
                .map((t) => t.trim())
                .filter(Boolean);
        const startDate = String(body.startDate || existing.startDate || "").trim();
        const endDate = String(body.endDate || existing.endDate || "").trim();
        const durationDays = Number(body.durationDays) || Number(existing.durationDays) || 3;
        const recreated = await PackingEngineService.createTripObject({
          destination: existing.destination || "Atlanta",
          durationDays,
          startDate,
          endDate,
          tags,
          customRules: state.custom_rules,
          geminiApiKey: body.geminiApiKey || this.env?.GEMINI_API_KEY || "",
        });
        recreated.tripMode = body.tripMode || existing.tripMode || "OUTBOUND";
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
        state.trips[targetIdx] = recreated;
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

