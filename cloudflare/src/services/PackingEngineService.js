/**
 * Weather-Aware Packing Engine & Natural-Language Assistant for PackTrack Cloudflare Worker.
 */

const PLUG_MAP = {
  US: "Type A/B",
  CA: "Type A/B",
  MX: "Type A/B",
  JP: "Type A/B",
  GB: "Type G",
  IE: "Type G",
  SG: "Type G",
  HK: "Type G",
  FR: "Type C/E",
  DE: "Type C/F",
  ES: "Type C/F",
  IT: "Type C/F/L",
  NL: "Type C/F",
  CH: "Type C/J",
  IN: "Type C/D/M",
  AU: "Type I",
  NZ: "Type I",
  CN: "Type A/C/I",
};

const SUGGESTED_PREFIX = "[SUGGESTED] ";

function describeWmoCode(code) {
  const c = Number(code) || 0;
  if (c === 0) return "Clear sky";
  if (c >= 1 && c <= 3) return "Partly cloudy";
  if (c === 45 || c === 48) return "Foggy";
  if (c >= 51 && c <= 57) return "Drizzle";
  if ([61, 63, 80, 81].includes(c)) return "Rain";
  if ([65, 66, 67, 82].includes(c)) return "Heavy rain";
  if ([71, 73, 77, 85].includes(c)) return "Snowfall";
  if ([75, 86].includes(c)) return "Heavy snow";
  if ([95, 96, 99].includes(c)) return "Thunderstorm";
  return "Mixed conditions";
}

export class PackingEngineService {
  static DEFAULT_MODEL_CASCADE = [
    "gemini-flash-latest",
    "gemini-3-flash-preview",
    "gemini-3.1-flash-lite-preview",
    "gemini-flash-lite-latest",
    "gemini-3.1-pro-preview",
    "gemini-pro-latest",
    "gemini-3.8-flash",
    "gemini-3.6-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
  ];

  static EMERGENCY_LAST_RESORT_MODELS = [
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
  ];

  static _cachedDiscoveredModels = null;
  static _cachedCascadeAt = 0;
  static _lastWorkingModel = null;

  /**
   * Multi-stage model classifier and scorer for `/v1beta/models` entries:
   *  1. Rejects non-Gemini families (`gemma-*`, `lyria-*`, `imagen-*`, `veo-*`, `aqa`, `text-embedding-*`)
   *  2. Rejects non-text / specialized media models that still support `generateContent`
   *     (`-image`, `nano-banana`, `omni`, `transcribe`, `tts`, `live`, `translate`, `robotics`, `computer-use`)
   *  3. Rejects legacy/deprecated `< 3.0` generations (`2.5`, `2.0`, `1.5`, `1.0`)
   *  4. Ranks by `(Tier Class → Release Stability → Major.Minor Version)`:
   *     - Tier 3 (Fast Structured Text Primary): `flash` (non-lite)
   *     - Tier 2 (Fast Structured Text Secondary): `flash-lite`
   *     - Tier 1 (Heavy Reasoning Fallback): `pro`, `argon`, `ultra`
   */
  static scoreGeminiModelId(rawId, metadata = null) {
    const id = String(rawId || "").replace(/^models\//, "").trim().toLowerCase();
    if (!id.startsWith("gemini-")) return -1;

    // Stage 1: Exclude specialized media/audio/agent/embedding models (many of which still list generateContent)
    if (
      /image|banana|omni|transcribe|tts|audio|video|music|lyria|veo|imagen|embedding|aqa|learnlm|robotics|computer-use|deep-research|antigravity|translate|thinking|live|vision|customtools/i.test(
        id
      )
    ) {
      return -1;
    }

    // Stage 2: Check metadata description/methods if full Model resource object was passed
    if (metadata && typeof metadata === "object") {
      if (
        Array.isArray(metadata.supportedGenerationMethods) &&
        !metadata.supportedGenerationMethods.includes("generateContent")
      ) {
        return -1;
      }
      const desc = String(metadata.description || "").toLowerCase();
      if (/shut down|deprecated|text-to-speech|speech-to-text|image generation|video generation/i.test(desc)) {
        return -1;
      }
      if (typeof metadata.outputTokenLimit === "number" && metadata.outputTokenLimit > 0 && metadata.outputTokenLimit < 2048) {
        return -1;
      }
    }

    // Stage 3: Identify tier class (must be an explicit general text tier: flash, flash-lite, pro, or argon)
    let tierScore = 0;
    if (id.includes("flash-lite") || id.includes("lite")) {
      tierScore = 20_000;
    } else if (id.includes("flash")) {
      tierScore = 30_000;
    } else if (id.includes("pro") || id.includes("argon") || id.includes("ultra")) {
      tierScore = 10_000;
    } else {
      return -1;
    }

    // Stage 4: Parse version or rolling `-latest` alias; reject legacy `< 3.0` models
    let versionScore = 0;
    const verMatch = id.match(/^gemini-(\d+)(?:\.(\d+))?-(?:flash-lite|flash|pro|argon|ultra)(?:$|-[a-z0-9-]+$)/);
    if (verMatch) {
      const major = parseInt(verMatch[1], 10) || 0;
      const minor = parseInt(verMatch[2] ?? "0", 10) || 0;
      if (major < 3) return -1; // Never select deprecated/legacy 1.x or 2.x models
      versionScore = major * 100 + minor * 10;
    } else if (id === "gemini-flash-latest" || id === "gemini-flash-lite-latest" || id === "gemini-pro-latest") {
      // Rolling alias acts as safety net right behind highest discovered explicit 3.x+ release of its tier
      versionScore = 375;
    } else {
      return -1;
    }

    // Stage 5: Stability weight (Stable GA > Rolling `-latest` > Pinned `-001` > `-preview` > `-exp`)
    let stabilityScore = 0;
    if (id.includes("exp") || id.includes("experimental")) {
      stabilityScore = 1000;
    } else if (id.includes("preview")) {
      stabilityScore = 2000;
    } else if (id.endsWith("-latest")) {
      stabilityScore = 4500;
    } else if (/^gemini-\d+(?:\.\d+)?-(?:flash-lite|flash|pro|argon|ultra)$/.test(id)) {
      stabilityScore = 5000;
    } else {
      stabilityScore = 4000;
    }

    return tierScore + stabilityScore + versionScore;
  }

  /**
   * Ranks a list of model metadata objects from `/v1beta/models` (or raw model ID strings)
   * from best/newest to fallback, and merges with `DEFAULT_MODEL_CASCADE` for resilience.
   */
  static rankModelsFromList(modelsList = []) {
    const scored = [];
    for (const entry of modelsList || []) {
      const rawName = typeof entry === "string" ? entry : entry?.name || entry?.baseModelId || "";
      const id = String(rawName).replace(/^models\//, "").trim();
      if (!id) continue;
      const metadata = typeof entry === "object" ? entry : null;
      const s = PackingEngineService.scoreGeminiModelId(id, metadata);
      if (s > 0) {
        scored.push({ id, score: s });
      }
    }

    scored.sort((a, b) => b.score - a.score);

    const cascade = [];
    const seen = new Set();
    const pushUnique = (modelId) => {
      const clean = String(modelId || "").replace(/^models\//, "").trim();
      if (clean && !seen.has(clean)) {
        seen.add(clean);
        cascade.push(clean);
      }
    };

    if (PackingEngineService._lastWorkingModel) {
      pushUnique(PackingEngineService._lastWorkingModel);
    }
    for (const item of scored) {
      pushUnique(item.id);
    }
    for (const fallbackId of PackingEngineService.DEFAULT_MODEL_CASCADE) {
      pushUnique(fallbackId);
    }
    return cascade;
  }

  /**
   * Dynamically discovers available Gemini models from `/v1beta/models` (cached for 1 hour)
   * so if Google introduces `gemini-4.3-flash` or `gemini-3.2-flash-lite` tomorrow, the cascade
   * automatically picks them up.
   */
  static async resolveModelCascade(geminiApiKey, fetchImpl = fetch) {
    const now = Date.now();
    if (
      PackingEngineService._cachedDiscoveredModels &&
      PackingEngineService._cachedDiscoveredModels.length > 0 &&
      now - PackingEngineService._cachedCascadeAt < 3600_000
    ) {
      return PackingEngineService.rankModelsFromList(PackingEngineService._cachedDiscoveredModels);
    }

    try {
      const listUrl = `https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(String(geminiApiKey).trim())}`;
      const resp = await fetchImpl(listUrl, { method: "GET" });
      if (resp.ok) {
        const data = await resp.json();
        if (Array.isArray(data?.models) && data.models.length > 0) {
          PackingEngineService._cachedDiscoveredModels = data.models;
          PackingEngineService._cachedCascadeAt = now;
          return PackingEngineService.rankModelsFromList(data.models);
        }
      }
    } catch (_) {
      // Ignore discovery failure and use default cascade
    }
    return PackingEngineService.rankModelsFromList([]);
  }

  /**
   * Strips optional markdown code fences and extracts valid JSON (`{...}` or `[...]`).
   */
  static extractCleanJson(rawText) {
    const cleaned = String(rawText || "")
      .replace(/^```(?:json)?\s*/i, "")
      .replace(/\s*```$/i, "")
      .trim();
    if (!cleaned) return null;
    try {
      JSON.parse(cleaned);
      return cleaned;
    } catch (_) {
      const objStart = cleaned.indexOf("{");
      const objEnd = cleaned.lastIndexOf("}");
      const arrStart = cleaned.indexOf("[");
      const arrEnd = cleaned.lastIndexOf("]");
      const candidates = [];
      if (objStart !== -1 && objEnd > objStart) candidates.push(cleaned.slice(objStart, objEnd + 1));
      if (arrStart !== -1 && arrEnd > arrStart) candidates.push(cleaned.slice(arrStart, arrEnd + 1));
      for (const cand of candidates) {
        try {
          JSON.parse(cand);
          return cand;
        } catch (_) {
          // Try next candidate
        }
      }
      return null;
    }
  }

  /**
   * Executes a `generateContent` request across the ranked model cascade, retrying transient
   * errors (429, 5xx, network, or truncated JSON) up to `maxRetriesPerModel` times per model
   * and automatically failing over across all fallback models.
   */
  static async callGeminiWithModelCascade(
    requestBody,
    geminiApiKey,
    { fetchImpl = fetch, cascadeOverride = null, maxRetriesPerModel = 2, retryDelayMs = null } = {}
  ) {
    const cleanKey = String(geminiApiKey || "").trim();
    if (!cleanKey) return null;

    const primaryCascade =
      Array.isArray(cascadeOverride) && cascadeOverride.length > 0
        ? cascadeOverride
        : await PackingEngineService.resolveModelCascade(cleanKey, fetchImpl);

    const fullCascade = Array.isArray(cascadeOverride) && cascadeOverride.length > 0
      ? primaryCascade
      : [...primaryCascade, ...PackingEngineService.EMERGENCY_LAST_RESORT_MODELS.filter((m) => !primaryCascade.includes(m))];

    const isLiveRuntime = typeof fetch !== "undefined" && fetchImpl === fetch && !cascadeOverride;
    const baseDelay =
      typeof retryDelayMs === "number"
        ? retryDelayMs
        : isLiveRuntime
        ? 250
        : 0;

    const sleep = (ms) => (ms > 0 ? new Promise((resolve) => setTimeout(resolve, ms)) : Promise.resolve());
    const startedAt = Date.now();
    const maxTotalNetworkCalls = isLiveRuntime ? 7 : 100;
    const maxTotalWallMs = isLiveRuntime ? 16000 : 300000;
    let totalNetworkCalls = 0;

    for (let mIdx = 0; mIdx < fullCascade.length; mIdx++) {
      const modelId = fullCascade[mIdx];
      let currentBody = requestBody;
      // In live Cloudflare Worker runtime, retry top 2 models up to maxRetriesPerModel, and try remaining fallback models once to stay well within Worker subrequest/time limits
      const effectiveMaxRetries = isLiveRuntime && mIdx >= 2 ? Math.min(1, maxRetriesPerModel) : maxRetriesPerModel;
      for (let attempt = 0; attempt <= effectiveMaxRetries; attempt++) {
        if (totalNetworkCalls >= maxTotalNetworkCalls || Date.now() - startedAt > maxTotalWallMs) {
          return null;
        }
        totalNetworkCalls++;
        try {
          const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(modelId)}:generateContent?key=${encodeURIComponent(cleanKey)}`;
          let controller = null;
          let timer = null;
          if (isLiveRuntime && typeof AbortController !== "undefined") {
            controller = new AbortController();
            timer = setTimeout(() => controller.abort(), 8500);
          }
          let resp;
          try {
            resp = await fetchImpl(url, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(currentBody),
              ...(controller ? { signal: controller.signal } : {}),
            });
          } finally {
            if (timer) clearTimeout(timer);
          }
          if (!resp.ok) {
            const status = Number(resp.status) || 0;
            // If a model rejects nested responseSchema with 400, retry immediately with plain JSON mode
            if (status === 400 && currentBody?.generationConfig?.responseSchema) {
              const { responseSchema, ...restGenConfig } = currentBody.generationConfig;
              currentBody = {
                ...currentBody,
                generationConfig: { ...restGenConfig, responseMimeType: "application/json" },
              };
              continue;
            }
            // 404 (model not found) or 403/401 -> immediately move to next model in cascade
            if (status === 404 || status === 401 || status === 403) {
              break;
            }
            // Retry on transient 429 / 5xx / 408 before failing over to next model
            if (attempt < effectiveMaxRetries) {
              await sleep(baseDelay * (attempt + 1));
              continue;
            }
            break;
          }
          const data = await resp.json();
          const rawText = data?.candidates?.[0]?.content?.parts?.[0]?.text || "";
          const validJson = PackingEngineService.extractCleanJson(rawText);
          if (validJson) {
            // Normalize the text part to the clean JSON string so callers can parse safely
            if (data?.candidates?.[0]?.content?.parts?.[0]) {
              data.candidates[0].content.parts[0].text = validJson;
            }
            PackingEngineService._lastWorkingModel = modelId;
            return { data, modelUsed: modelId, attemptsOnModel: attempt + 1 };
          }
          // Truncated or empty response -> retry before moving to next model
          if (attempt < effectiveMaxRetries) {
            await sleep(baseDelay * (attempt + 1));
          }
        } catch (_) {
          if (attempt < effectiveMaxRetries) {
            await sleep(baseDelay * (attempt + 1));
          }
        }
      }
    }
    return null;
  }

  static async fetchDestinationWeather(destination, durationDays = 5, startDate = "", endDate = "") {
    const cleanDest = String(destination || "Atlanta").trim();
    const fallback = {
      city: cleanDest,
      country: "United States",
      countryCode: "US",
      avgHighF: 68.0,
      avgLowF: 52.0,
      maxHighF: 68.0,
      minLowF: 52.0,
      rainyDaysCount: 0,
      hasSnow: false,
      maxWindMph: 10.0,
      maxUvIndex: 5.0,
      dailyBreakdown: [],
      plugTypes: "Type A/B",
      isInternational: false,
    };

    try {
      const geoUrl = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(cleanDest)}&count=1&language=en&format=json`;
      const geoResp = await fetch(geoUrl);
      if (!geoResp.ok) return fallback;
      const geoData = await geoResp.json();
      const first = geoData?.results?.[0];
      if (!first) return fallback;

      const lat = first.latitude;
      const lon = first.longitude;
      const city = first.name || cleanDest;
      const country = first.country || "United States";
      const countryCode = (first.country_code || "US").toUpperCase();
      const plugTypes = PLUG_MAP[countryCode] || (countryCode === "US" ? "Type A/B" : "Universal Adapter");
      const isInternational = countryCode !== "US";

      const days = Math.max(1, Math.min(14, Number(durationDays) || 5));
      const cleanStart = /^\d{4}-\d{2}-\d{2}$/.test(String(startDate || "").trim()) ? String(startDate).trim() : "";
      const cleanEnd = /^\d{4}-\d{2}-\d{2}$/.test(String(endDate || "").trim()) ? String(endDate).trim() : "";
      let wxUrl = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,snowfall_sum,wind_speed_10m_max,uv_index_max&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=${days}`;
      if (cleanStart) {
        const todayMs = new Date(new Date().toISOString().slice(0, 10) + "T00:00:00Z").getTime();
        const startMs = new Date(cleanStart + "T00:00:00Z").getTime();
        const diffDays = Math.round((startMs - todayMs) / 86400000);
        const computedEnd = cleanEnd || new Date(startMs + (days - 1) * 86400000).toISOString().slice(0, 10);
        if (diffDays >= 0 && diffDays <= 14) {
          const maxEndIso = new Date(todayMs + 15 * 86400000).toISOString().slice(0, 10);
          const safeEnd = computedEnd > maxEndIso ? maxEndIso : (computedEnd < cleanStart ? cleanStart : computedEnd);
          wxUrl = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,snowfall_sum,wind_speed_10m_max,uv_index_max&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&start_date=${cleanStart}&end_date=${safeEnd}`;
        } else if (diffDays > 14) {
          const histStart = `${Number(cleanStart.slice(0, 4)) - 1}${cleanStart.slice(4)}`;
          const histEnd = `${Number(computedEnd.slice(0, 4)) - 1}${computedEnd.slice(4)}`;
          wxUrl = `https://archive-api.open-meteo.com/v1/archive?latitude=${lat}&longitude=${lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,snowfall_sum,wind_speed_10m_max&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&start_date=${histStart}&end_date=${histEnd}`;
        }
      }
      const wxResp = await fetch(wxUrl);
      if (!wxResp.ok) {
        return { ...fallback, city, country, countryCode, plugTypes, isInternational };
      }
      const wxData = await wxResp.json();
      const daily = wxData?.daily || {};
      const times = daily.time || [];
      const codes = daily.weather_code || [];
      const highs = daily.temperature_2m_max || [68.0];
      const lows = daily.temperature_2m_min || [52.0];
      const precip = daily.precipitation_sum || [0];
      const rainProb = daily.precipitation_probability_max || [0];
      const snow = daily.snowfall_sum || [0];
      const wind = daily.wind_speed_10m_max || [10];
      const uv = daily.uv_index_max || [5];

      const avgHighF = Math.round((highs.reduce((a, b) => a + b, 0) / highs.length) * 10) / 10;
      const avgLowF = Math.round((lows.reduce((a, b) => a + b, 0) / lows.length) * 10) / 10;
      const maxHighF = Math.max(...highs);
      const minLowF = Math.min(...lows);
      const maxWindMph = Math.max(...wind);
      const maxUvIndex = Math.max(...uv);

      let rainyDaysCount = 0;
      let hasSnow = false;
      const dailyBreakdown = [];

      for (let i = 0; i < highs.length; i++) {
        const hi = Math.round(highs[i]);
        const lo = Math.round(lows[i]);
        const pMm = Number(precip[i]) || 0;
        const rProb = Number(rainProb[i]) || 0;
        const sCm = Number(snow[i]) || 0;
        const wMph = Math.round(Number(wind[i]) || 0);
        const uvIdx = Math.round(Number(uv[i]) || 0);
        const wCode = Number(codes[i]) || 0;
        const dateStr = times[i] || `Day ${i + 1}`;

        if (rProb >= 40 || pMm > 1.5 || [51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99].includes(wCode)) {
          rainyDaysCount++;
        }
        if (sCm > 0.1 || [71, 73, 75, 77, 85, 86].includes(wCode)) {
          hasSnow = true;
        }

        const condLabel = describeWmoCode(wCode);
        const extras = [];
        if (rProb > 0) extras.push(`${rProb}% precip`);
        if (sCm > 0.1) extras.push(`${sCm.toFixed(1)}cm snow`);
        if (wMph >= 18) extras.push(`wind ${wMph}mph`);
        if (uvIdx >= 7) extras.push(`UV ${uvIdx}`);
        dailyBreakdown.push(
          `Day ${i + 1} (${dateStr}): ${lo}°F–${hi}°F, ${condLabel}${extras.length ? " (" + extras.join(", ") + ")" : ""}`
        );
      }

      return {
        city,
        country,
        countryCode,
        avgHighF,
        avgLowF,
        maxHighF,
        minLowF,
        rainyDaysCount,
        hasSnow,
        maxWindMph,
        maxUvIndex,
        dailyBreakdown,
        plugTypes,
        isInternational,
      };
    } catch (_) {
      return fallback;
    }
  }

  /**
   * Builds ONLY the user's Default Template items first (matched against conditions & scaled per day).
   */
  static buildTripChecklist({ weather, durationDays, tags, customRules }) {
    const days = Math.max(1, Number(durationDays) || 3);
    const tagSet = new Set((tags || []).map((t) => String(t).toLowerCase().trim()));
    if (weather?.isInternational) {
      tagSet.add("international");
    }
    const isPersonalCar = tagSet.has("own_car") || tagSet.has("personal_car");
    const isFlight = !isPersonalCar;
    if (isFlight) {
      tagSet.add("flight");
    }

    const effectiveDays =
      isPersonalCar || weather?.isInternational || tagSet.has("checked_bag") || tagSet.has("two_checked_bags")
        ? days
        : tagSet.has("personal_only")
        ? Math.min(days, 3)
        : Math.min(days, 6);

    const items = [];
    const seen = new Set();

    const addItem = (spec) => {
      const key = String(spec.name || "").trim().toLowerCase();
      if (!key || seen.has(key)) return;
      seen.add(key);
      items.push({
        name: spec.name,
        category: spec.category || "Others",
        quantity: Math.max(1, Number(spec.quantity) || 1),
        bagLocation: spec.bagLocation || "Carry-On",
        packedOutbound: Boolean(spec.packedOutbound),
        packedReturn: Boolean(spec.packedReturn),
        isDoorwayCritical: Boolean(spec.isDoorwayCritical),
        isHighLossReturn: Boolean(spec.isHighLossReturn),
        weatherReason: spec.weatherReason || "",
      });
    };

    const nlRuleRegex = /^if\s+(.+?),\s*(.+?)\s+should\s+be\s+added\.?$/i;
    for (const rule of customRules || []) {
      const rawName = String(rule.name || "").trim();
      const nlMatch = rawName.match(nlRuleRegex);
      const rawCondition =
        nlMatch && (!rule.moduleKey || String(rule.moduleKey).trim().toLowerCase() === "always")
          ? nlMatch[1]
          : String(rule.moduleKey || "Always");
      const effectiveName = nlMatch
        ? nlMatch[2].trim().replace(/^./, (c) => c.toUpperCase())
        : rawName;

      const shouldInclude = PackingEngineService.evaluateRuleCondition({
        rawCondition,
        durationDays: days,
        tags: Array.from(tagSet),
        weather,
      });

      if (!shouldInclude) continue;

      let qty = 1;
      if (rule.scaleByDays) {
        const ratio = Number(rule.ratioPerDay) || 1.0;
        qty = Math.max(1, Math.ceil(effectiveDays * ratio));
      }

      addItem({
        name: effectiveName,
        category: rule.category,
        quantity: qty,
        bagLocation: rule.bagLocation || "Carry-On",
        isDoorwayCritical: rule.isDoorwayCritical,
        isHighLossReturn: rule.isHighLossReturn,
        weatherReason: rule.scaleByDays ? `Scaled for ${effectiveDays}d` : "",
      });
    }

    return items;
  }

  /**
   * Multi-clause compound condition evaluator used as deterministic baseline & offline fallback.
   * Evaluates ALL constraints present in `rawCondition` (AND semantics):
   *  - Transport / vehicle (`personal car` / `own car`, `rental`, `flight` / `flying`)
   *  - Purpose (`work` / `office` / `business`)
   *  - International (`international`)
   *  - Weather (`rain` / `wet`, `cold` / `winter`, `sun` / `warm` / `hot`, `snow`)
   *  - Duration (`more than N days`, `at least N days`, `less than N days`)
   *  - Destination city/country (e.g. `go to India`, `in Japan`, `to London`)
   */
  static evaluateRuleCondition({ rawCondition, durationDays = 3, tags = [], weather = {} }) {
    const condLower = String(rawCondition || "").trim().toLowerCase();
    if (!condLower || condLower === "always" || condLower === "default") return true;

    const days = Math.max(1, Number(durationDays) || 3);
    const tagSet = new Set((tags || []).map((t) => String(t).toLowerCase().trim()));
    const isPersonalCar = tagSet.has("own_car") || tagSet.has("personal_car");
    const isFlight = !isPersonalCar;
    const isWork = tagSet.has("work") || tagSet.has("office") || tagSet.has("business");

    // 1. Personal car vs Rental car vs Flight
    if (condLower.includes("personal car") || condLower.includes("own car") || condLower === "own_car" || condLower === "personal_car") {
      if (!isPersonalCar) return false;
    }
    if (condLower.includes("rental") || condLower === "rental_car") {
      if (!tagSet.has("rental_car")) return false;
    }
    if (condLower.includes("flight") || condLower.includes("fly") || condLower === "carry_on" || condLower === "checked_bag") {
      if (!isFlight) return false;
    }

    // 2. Work / Office / Business
    if (condLower.includes("work") || condLower.includes("office") || condLower.includes("business")) {
      if (!isWork) return false;
    }

    // 3. International
    if (condLower.includes("international")) {
      if (!weather?.isInternational) return false;
    }

    // 4. Weather clauses
    if (/\b(rain|rainy|wet|drizzle)\b/.test(condLower)) {
      const minRainMatch = condLower.match(/(?:more than|at\s*least|>=?)\s*(\d+)\s*rain/);
      const minRainDays = minRainMatch ? parseInt(minRainMatch[1], 10) || 1 : 1;
      if ((weather?.rainyDaysCount ?? 0) < minRainDays) return false;
    }
    if (/\b(cold|winter|freezing|chilly)\b/.test(condLower)) {
      if ((weather?.avgLowF ?? 60) > 58.0) return false;
    }
    if (/\b(snow|snowy|blizzard)\b/.test(condLower)) {
      if (!weather?.hasSnow && (weather?.minLowF ?? weather?.avgLowF ?? 60) > 32.0) return false;
    }
    if (/\b(sun|sunny|warm|hot)\b/.test(condLower)) {
      if ((weather?.avgHighF ?? 70) < 68.0 && (weather?.rainyDaysCount ?? 0) > 0) return false;
    }

    // 5. Duration clauses ("more than 5 days", "at least 4 days", "less than 3 days")
    const moreDaysMatch = condLower.match(/(?:more than|greater than|over|>)\s*(\d+)\s*day/);
    if (moreDaysMatch && days <= (parseInt(moreDaysMatch[1], 10) || 0)) return false;

    const minDaysMatch = condLower.match(/(?:at\s*least|minimum|>=)\s*(\d+)\s*day/);
    if (minDaysMatch && days < (parseInt(minDaysMatch[1], 10) || 0)) return false;

    const lessDaysMatch = condLower.match(/(?:less than|under|fewer than|<)\s*(\d+)\s*day/);
    if (lessDaysMatch && days >= (parseInt(lessDaysMatch[1], 10) || 999)) return false;

    // 6. Destination city / country clauses (e.g., "when we go to India", "in Tokyo", "visiting UK")
    const destRegex = /\b(?:go(?:ing)?\s+to|trip\s+to|travel(?:ing)?\s+to|visit(?:ing)?|in)\s+([a-z][a-z\s]{1,24}?)(?=\s+(?:and|or|if|when|for|with|$)|[,.]|$)/g;
    const ignoreTokens = new Set([
      "personal car", "own car", "rental car", "a car", "car", "flight", "plane",
      "work", "office", "rain", "cold", "winter", "summer", "the beach", "hotel",
    ]);
    let match;
    while ((match = destRegex.exec(condLower)) !== null) {
      const candidate = match[1].trim();
      if (!ignoreTokens.has(candidate) && !candidate.endsWith("days") && !candidate.endsWith("day")) {
        const cityLower = String(weather?.city || "").toLowerCase();
        const countryLower = String(weather?.country || "").toLowerCase();
        const codeLower = String(weather?.countryCode || "").toLowerCase();
        const matchesPlace =
          cityLower.includes(candidate) || countryLower.includes(candidate) || codeLower === candidate;
        if (!matchesPlace) return false;
      }
    }

    return true;
  }

  static buildAllowedCategories(extraSources = []) {
    const base = ["On Me", "Docs", "Electronics", "Clothes", "Toiletry", "Others"];
    const seen = new Set(base.map((c) => c.toLowerCase()));
    const result = [...base];
    for (const raw of extraSources) {
      const clean = String(raw || "").trim();
      if (clean && !seen.has(clean.toLowerCase())) {
        seen.add(clean.toLowerCase());
        result.push(clean);
      }
    }
    return result;
  }

  static isUsaOrIndia(country, countryCode, plugTypes) {
    const code = String(countryCode || "").trim().toUpperCase();
    if (["US", "USA", "IN", "IND"].includes(code)) return true;
    const c = String(country || "").trim().toLowerCase();
    if (["united states", "united states of america", "usa", "us", "u.s.", "u.s.a.", "india", "in", "bharat"].includes(c)) {
      return true;
    }
    const p = String(plugTypes || "").trim();
    if (!c && !code && (p === "Type A/B" || p.startsWith("Type A/B (120V)") || p.startsWith("Type C/D/M"))) {
      return true;
    }
    return false;
  }

  static isPlugInfoNeeded(country, countryCode, plugTypes) {
    if (PackingEngineService.isUsaOrIndia(country, countryCode, plugTypes)) return false;
    return Boolean(String(plugTypes || "").trim());
  }

  /**
   * Builds the exact Gemini `generateContent` request body using native Structured Outputs
   * (`responseMimeType: "application/json"` + `responseSchema`) so Gemini is constrained
   * at token-sampling time to return a valid JSON array matching our schema (including any
   * user-created custom categories).
   */
  static buildGeminiSuggestionRequestPayload({ weather, durationDays, tags = [], templateItems = [], customCategories = [] }) {
    const tagSet = new Set((tags || []).map((t) => String(t).toLowerCase().trim()));
    const isPersonalCar = tagSet.has("own_car") || tagSet.has("personal_car");
    const isRentalCar = tagSet.has("rental_car");
    const isWork = tagSet.has("work") || tagSet.has("office") || tagSet.has("business");

    const allowedCategories = PackingEngineService.buildAllowedCategories([
      ...(templateItems || []).map((i) => i?.category),
      ...(customCategories || []),
    ]);

    const dailyLines = (weather?.dailyBreakdown || []).length
      ? weather.dailyBreakdown.map((d) => `  - ${d}`).join("\n")
      : `  - Average ${Math.round(weather?.avgLowF ?? 52)}°F low to ${Math.round(weather?.avgHighF ?? 68)}°F high, ${weather?.rainyDaysCount ?? 0} rainy day(s)`;
    const packedList = (templateItems || []).map((i) => `${i.name} (x${i.quantity})`).join(", ");
    const showPlug = PackingEngineService.isPlugInfoNeeded(weather?.country, weather?.countryCode, weather?.plugTypes);

    const prompt = [
      "You are PackTrack's dynamic weather & travel advisor.",
      "The user's Default Template items have ALREADY been added to their packing checklist.",
      "Analyze the day-by-day weather forecast, destination, and vehicle/transport mode to suggest 2 to 5 specific, practical additional items or preparations that are NOT already in their template checklist.",
      "",
      "TRIP DETAILS:",
      `- Destination: ${weather?.city}, ${weather?.country} (International: ${Boolean(weather?.isInternational)}${showPlug ? `, Plug: ${weather?.plugTypes}` : ""})`,
      `- Duration: ${durationDays} days`,
      `- Transport / Vehicle: ${isPersonalCar ? "Driving Personal Car" : isRentalCar ? "Flight + Rental Car" : "Flight / Transit Only"}`,
      `- Purpose: ${isWork ? "Work / Business" : "Leisure"}`,
      `- Allowed Categories: ${allowedCategories.join(", ")}`,
      "",
      "DAY-BY-DAY WEATHER FORECAST:",
      dailyLines,
      "",
      "ITEMS ALREADY IN CHECKLIST FROM USER'S TEMPLATE (DO NOT DUPLICATE):",
      packedList || "None",
      "",
      "INSTRUCTIONS:",
      `- Suggest 2 to 5 specific items or preparations tailored to this forecast and trip mode (e.g., if rain is expected on specific days, suggest a raincoat/compact umbrella and mention which days; if very hot or high UV on a day, suggest sunscreen/sun hat/hydration; if snow or freezing weather is expected while driving, suggest snow tires/chains or an ice scraper; if windy, suggest a windbreaker; ${showPlug ? `suggest a ${weather?.plugTypes} power adapter` : "do NOT suggest a power/plug adapter for USA or India trips"}).`,
      "- Make each `reason` conversational, helpful, and specific to the actual forecast days/temperatures.",
    ].join("\n");

    return {
      contents: [{ role: "user", parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0.2,
        responseMimeType: "application/json",
        responseSchema: {
          type: "ARRAY",
          description: "2 to 5 context-aware weather and trip packing suggestions not already in the template.",
          items: {
            type: "OBJECT",
            properties: {
              name: { type: "STRING", description: "Concise item name, e.g. 'Raincoat / Compact Umbrella'" },
              category: {
                type: "STRING",
                enum: allowedCategories,
              },
              quantity: { type: "INTEGER", description: "Suggested quantity (>= 1)" },
              bagLocation: {
                type: "STRING",
                enum: ["Personal", "Carry-On", "Check-In"],
              },
              reason: {
                type: "STRING",
                description: "Specific day/temperature explanation for why this item is recommended.",
              },
            },
            required: ["name", "category", "quantity", "bagLocation", "reason"],
          },
        },
      },
    };
  }

  /**
   * Parses and validates a Gemini `generateContent` JSON response against our item contract,
   * normalizing categories (including user-created custom categories), bag locations,
   * clamping quantities >= 1, and filtering duplicates.
   */
  static parseGeminiSuggestionResponse(
    responseData,
    { existingNames = new Set(), city = "destination", allowedCategories = null, suppressPlug = false } = {}
  ) {
    const rawText = responseData?.candidates?.[0]?.content?.parts?.[0]?.text || "";
    const start = rawText.indexOf("[");
    const end = rawText.lastIndexOf("]");
    if (start === -1 || end <= start) return [];

    const arr = JSON.parse(rawText.slice(start, end + 1));
    if (!Array.isArray(arr)) return [];

    const validCategories = new Set(
      PackingEngineService.buildAllowedCategories(allowedCategories || []).map((c) => c.toLowerCase())
    );
    const canonicalMap = new Map(
      PackingEngineService.buildAllowedCategories(allowedCategories || []).map((c) => [c.toLowerCase(), c])
    );
    const validBags = new Set(["Personal", "Carry-On", "Check-In"]);
    const suggestions = [];
    const seen = new Set();

    for (const obj of arr) {
      if (!obj || typeof obj !== "object") continue;
      const name = String(obj.name || "").trim();
      if (!name) continue;
      const key = name.toLowerCase();
      if (suppressPlug && (key.includes("adapter") || key.includes("plug converter"))) continue;
      if (existingNames.has(key) || seen.has(key)) continue;
      seen.add(key);

      const rawCat = String(obj.category || "Others").trim();
      const category = validCategories.has(rawCat.toLowerCase())
        ? canonicalMap.get(rawCat.toLowerCase())
        : "Others";
      const rawBag = String(obj.bagLocation || "Carry-On").trim();
      const bagLocation = validBags.has(rawBag) ? rawBag : "Carry-On";
      const quantity = Math.max(1, Math.min(30, parseInt(obj.quantity, 10) || 1));
      const rawReason = String(obj.reason || `Suggested for ${city} weather`)
        .replace(/^\[SUGGESTED\]\s*/i, "")
        .trim();

      suggestions.push({
        name,
        category,
        quantity,
        bagLocation,
        packedOutbound: false,
        packedReturn: false,
        isDoorwayCritical: false,
        isHighLossReturn: true,
        weatherReason: `${SUGGESTED_PREFIX}${rawReason}`,
      });
    }
    return suggestions;
  }

  /**
   * Calls Gemini (`gemini-2.5-flash`) with the day-by-day forecast, destination, and vehicle mode
   * to generate dynamic suggested items (tagged with `[SUGGESTED] `) that appear at the end of the checklist.
   * Falls back to a day-by-day forecast analyzer if no API key is configured.
   */
  static async generateGeminiSuggestions({ weather, durationDays, tags = [], templateItems = [], geminiApiKey = "" }) {
    const existingNames = new Set((templateItems || []).map((i) => String(i.name || "").trim().toLowerCase()));
    const tagSet = new Set((tags || []).map((t) => String(t).toLowerCase().trim()));
    const isPersonalCar = tagSet.has("own_car") || tagSet.has("personal_car");
    const isRentalCar = tagSet.has("rental_car");
    const isDriving = isPersonalCar || isRentalCar;
    const suppressPlug = PackingEngineService.isUsaOrIndia(weather?.country, weather?.countryCode, weather?.plugTypes);

    if (geminiApiKey && String(geminiApiKey).trim()) {
      try {
        const requestBody = PackingEngineService.buildGeminiSuggestionRequestPayload({
          weather,
          durationDays,
          tags,
          templateItems,
        });

        const cascadeResult = await PackingEngineService.callGeminiWithModelCascade(requestBody, geminiApiKey);
        if (cascadeResult?.data) {
          const suggestions = PackingEngineService.parseGeminiSuggestionResponse(cascadeResult.data, {
            existingNames,
            city: weather?.city || "destination",
            suppressPlug,
          });
          if (suggestions.length > 0) {
            return suggestions;
          }
        }
      } catch (_) {
        // Fall through to dynamic day-by-day forecast analyzer
      }
    }

    const suggestions = [];
    const hasMatch = (substrs) =>
      Array.from(existingNames).some((n) => substrs.some((s) => n.includes(s)));

    const addSuggestion = (name, category, bagLocation, reason) => {
      const key = name.toLowerCase();
      if (existingNames.has(key)) return;
      suggestions.push({
        name,
        category,
        quantity: 1,
        bagLocation,
        packedOutbound: false,
        packedReturn: false,
        isDoorwayCritical: false,
        isHighLossReturn: true,
        weatherReason: `${SUGGESTED_PREFIX}${reason}`,
      });
    };

    const breakdown = weather?.dailyBreakdown || [];
    const rainLine = breakdown.find((l) => /rain|drizzle|thunderstorm/i.test(l));
    const rainDayRef = rainLine ? rainLine.split(":")[0] : null;

    if ((weather?.rainyDaysCount ?? 0) > 0 && !hasMatch(["umbrella", "rain", "poncho"])) {
      const dayPhrase = rainDayRef
        ? `starting ${rainDayRef} (${weather.rainyDaysCount} rainy day(s) forecast)`
        : `across ${weather.rainyDaysCount} day(s)`;
      addSuggestion(
        "Raincoat / Compact Umbrella",
        "Others",
        "Carry-On",
        `We expect rain in ${weather.city} ${dayPhrase} — carry a waterproof jacket or compact umbrella.`
      );
    }

    const minLow = weather?.minLowF ?? weather?.avgLowF ?? 52;
    const maxHigh = weather?.maxHighF ?? weather?.avgHighF ?? 68;

    if (weather?.hasSnow || minLow <= 32) {
      const snowLine = breakdown.find((l) => /snow/i.test(l));
      const snowDayRef = snowLine ? snowLine.split(":")[0] : "during your trip";
      if (isDriving) {
        addSuggestion(
          "Ice Scraper / Snow Tire Check",
          "Others",
          "Personal",
          `Freezing lows (${Math.round(minLow)}°F) ${weather?.hasSnow ? `and snow ${snowDayRef} ` : ""}while driving — snow tires/chains or an ice scraper might be needed.`
        );
      }
      if (!hasMatch(["thermal", "glove", "beanie"])) {
        addSuggestion(
          "Thermal Base Layer & Gloves",
          "Clothes",
          "Carry-On",
          `Temperatures drop to ${Math.round(minLow)}°F in ${weather.city} — pack insulated layers, gloves, and a warm hat.`
        );
      }
    } else if (minLow <= 48 && !hasMatch(["thermal", "jacket", "coat", "fleece", "sweater"])) {
      addSuggestion(
        "Warm Layer / Light Jacket",
        "Clothes",
        "Carry-On",
        `Chilly lows down to ${Math.round(minLow)}°F in ${weather.city} — pack a warm layer or fleece for evenings.`
      );
    }

    if (maxHigh >= 84 || (weather?.maxUvIndex ?? 0) >= 7.5) {
      const hotLine = breakdown.find((l) => l.includes(`${Math.round(maxHigh)}°F`));
      const hotDayRef = hotLine ? `on ${hotLine.split(":")[0]}` : "during peak afternoon hours";
      if (!hasMatch(["hat", "cap", "water bottle", "electrolyte"])) {
        addSuggestion(
          "Sun Hat & Reusable Water Bottle",
          "Others",
          "Personal",
          `Highs reach ${Math.round(maxHigh)}°F ${hotDayRef}${(weather?.maxUvIndex ?? 0) >= 7 ? ` (UV ${Math.round(weather.maxUvIndex)})` : ""} — stay hydrated and pack sun protection.`
        );
      }
    }

    if ((weather?.maxWindMph ?? 0) >= 20 && !hasMatch(["windbreaker", "shell"])) {
      addSuggestion(
        "Windbreaker Shell",
        "Clothes",
        "Carry-On",
        `Breezy gusts up to ${Math.round(weather.maxWindMph)} mph forecast in ${weather.city} — a windproof outer layer will keep you comfortable.`
      );
    }

    if (
      PackingEngineService.isPlugInfoNeeded(weather?.country, weather?.countryCode, weather?.plugTypes) &&
      !hasMatch(["adapter"])
    ) {
      addSuggestion(
        `Power Adapter (${weather.plugTypes})`,
        "Electronics",
        "Personal",
        `${weather.country} uses ${weather.plugTypes} outlets — pack a compatible travel adapter.`
      );
    }

    if (suggestions.length === 0 && !hasMatch(["water bottle", "light layer"])) {
      addSuggestion(
        "Collapsible Water Bottle & Light Layer",
        "Others",
        "Personal",
        `Mild ${Math.round(weather?.avgLowF ?? 52)}°F–${Math.round(weather?.avgHighF ?? 68)}°F forecast in ${weather?.city} — handy for day outings and temperature swings.`
      );
    }

    return suggestions;
  }

  static async evaluateTemplateAndSuggestWithGemini({ weather, durationDays = 3, tags = [], customRules = [], geminiApiKey = "", fetchImpl = fetch }) {
    const fallbackTemplateItems = PackingEngineService.buildTripChecklist({
      weather,
      durationDays,
      tags,
      customRules,
    });
    const showPlug = PackingEngineService.isPlugInfoNeeded(weather?.country, weather?.countryCode, weather?.plugTypes);
    const suppressPlug = !showPlug;

    if (geminiApiKey && String(geminiApiKey).trim()) {
      try {
        const days = Math.max(1, Number(durationDays) || 3);
        const tagSet = new Set((tags || []).map((t) => String(t).toLowerCase().trim()));
        const isPersonalCar = tagSet.has("own_car") || tagSet.has("personal_car");
        const isRentalCar = tagSet.has("rental_car");
        const isWork = tagSet.has("work") || tagSet.has("office") || tagSet.has("business");
        const effectiveDays =
          isPersonalCar || weather?.isInternational || tagSet.has("checked_bag") || tagSet.has("two_checked_bags")
            ? days
            : tagSet.has("personal_only")
            ? Math.min(days, 3)
            : Math.min(days, 6);

        const dailyLines = (weather?.dailyBreakdown || []).length
          ? weather.dailyBreakdown.map((d) => `  - ${d}`).join("\n")
          : `  - Average ${Math.round(weather?.avgLowF ?? 52)}°F low to ${Math.round(weather?.avgHighF ?? 68)}°F high, ${weather?.rainyDaysCount ?? 0} rainy day(s)`;

        const rulesLines = (customRules || [])
          .map((r) => {
            const scaleDesc = r.scaleByDays
              ? `scale_by_days=true (ratio=${r.ratioPerDay || 1}/day, effectiveDays=${effectiveDays})`
              : "fixed_qty=1";
            return `  - Item: "${r.name}" | Category: "${r.category || "Others"}" | Bag: "${r.bagLocation || "Carry-On"}" | Condition: "${r.moduleKey || "Always"}" | Scaling: ${scaleDesc}`;
          })
          .join("\n");

        const prompt = [
          "You are PackTrack's intelligent packing rule evaluator and weather advisor.",
          "",
          "TASK 1 (`templateItems` — Evaluate User's Default Template Rules):",
          "Evaluate EVERY item in the user's Default Template below against the TRIP DETAILS and DAY-BY-DAY WEATHER FORECAST.",
          '- Include an item in `templateItems` IF AND ONLY IF its `Condition` is satisfied for this trip (e.g., "Always" is always included; "Only when working" or "if work is expected to be done" is included only when Purpose is Work; complex natural-language conditions like "Only add this when there is at least one rainy day and if trip is for more than 5 days and only when we go to India" are included only when ALL parts of that condition hold true for this trip).',
          "- If `scale_by_days=true`, compute `quantity = ceil(effectiveDays * ratio)`. Otherwise `quantity = 1`. Keep `reason` concise (max 8 words) for templateItems.",
          "",
          "TASK 2 (`suggestedItems` — Dynamic Weather & Trip Suggestions):",
          `Suggest 2 to 5 additional practical items or preparations tailored to the day-by-day forecast, destination, and vehicle mode that are NOT already in \`templateItems\` (e.g., raincoat/umbrella citing rainy days, snow tires/ice scraper if freezing/snowing while driving, sun hat/sunscreen on high-UV/hot days, windbreaker on windy days${showPlug ? `, or ${weather?.plugTypes} power adapter` : "; do NOT suggest a power/plug adapter for USA or India trips"}).`,
          "",
          "TRIP DETAILS:",
          `- Destination City: ${weather?.city}`,
          `- Destination Country: ${weather?.country} (Country Code: ${weather?.countryCode}, International: ${Boolean(weather?.isInternational)}${showPlug ? `, Plug: ${weather?.plugTypes}` : ""})`,
          `- Duration: ${days} days (Effective clothing scaling days: ${effectiveDays})`,
          `- Transport / Vehicle: ${isPersonalCar ? "Driving Personal Car" : isRentalCar ? "Flight + Rental Car" : "Flight / Transit Only"}`,
          `- Purpose: ${isWork ? "Work / Business (isWork=true)" : "Leisure / Personal (isWork=false)"}`,
          "",
          `DAY-BY-DAY WEATHER FORECAST (Avg Low: ${Math.round(weather?.avgLowF ?? 52)}°F, Avg High: ${Math.round(weather?.avgHighF ?? 68)}°F, Rainy Days: ${weather?.rainyDaysCount ?? 0}):`,
          dailyLines,
          "",
          "USER'S DEFAULT TEMPLATE RULES:",
          rulesLines || "  None",
        ].join("\n");

        const allowedCategories = PackingEngineService.buildAllowedCategories(
          (customRules || []).map((r) => r?.category)
        );

        const itemObjectSchema = {
          type: "OBJECT",
          properties: {
            name: { type: "STRING" },
            category: {
              type: "STRING",
              enum: allowedCategories,
            },
            quantity: { type: "INTEGER" },
            bagLocation: {
              type: "STRING",
              enum: ["Personal", "Carry-On", "Check-In"],
            },
            reason: { type: "STRING" },
          },
          required: ["name", "category", "quantity", "bagLocation", "reason"],
        };

        const requestBody = {
          contents: [{ role: "user", parts: [{ text: prompt }] }],
          generationConfig: {
            temperature: 0.2,
            responseMimeType: "application/json",
            responseSchema: {
              type: "OBJECT",
              properties: {
                templateItems: { type: "ARRAY", items: itemObjectSchema },
                suggestedItems: { type: "ARRAY", items: itemObjectSchema },
              },
              required: ["templateItems", "suggestedItems"],
            },
          },
        };

        const cascadeResult = await PackingEngineService.callGeminiWithModelCascade(requestBody, geminiApiKey, { fetchImpl });
        if (cascadeResult?.data) {
          const data = cascadeResult.data;
          const rawText = data?.candidates?.[0]?.content?.parts?.[0]?.text || "";
          const cleanJson = PackingEngineService.extractCleanJson(rawText) || rawText;
          const parsedRoot = JSON.parse(cleanJson);
          if (parsedRoot && Array.isArray(parsedRoot.templateItems)) {
            const ruleByLowerName = new Map(
              (customRules || []).map((r) => [String(r.name || "").trim().toLowerCase(), r])
            );
            const seen = new Set();
            const templateItems = [];
            for (const obj of parsedRoot.templateItems) {
              const name = String(obj?.name || "").trim();
              if (!name) continue;
              const key = name.toLowerCase();
              if (seen.has(key)) continue;
              seen.add(key);
              const matchedRule = ruleByLowerName.get(key);
              templateItems.push({
                name: matchedRule?.name || name,
                category: obj?.category || matchedRule?.category || "Others",
                quantity: Math.max(1, Math.min(30, parseInt(obj?.quantity, 10) || 1)),
                bagLocation: obj?.bagLocation || matchedRule?.bagLocation || "Carry-On",
                packedOutbound: false,
                packedReturn: false,
                isDoorwayCritical: Boolean(matchedRule?.isDoorwayCritical),
                isHighLossReturn: matchedRule?.isHighLossReturn !== false,
                weatherReason: String(obj?.reason || "").replace(/^\[SUGGESTED\]\s*/i, "").trim(),
              });
            }

            // Ensure any "Always" rules are preserved
            for (const fb of fallbackTemplateItems) {
              const key = String(fb.name || "").trim().toLowerCase();
              const rule = ruleByLowerName.get(key);
              const cond = String(rule?.moduleKey || "Always").trim().toLowerCase();
              if ((!cond || cond === "always") && !seen.has(key)) {
                seen.add(key);
                templateItems.push(fb);
              }
            }

            const suggestedItems = [];
            for (const obj of parsedRoot.suggestedItems || []) {
              const name = String(obj?.name || "").trim();
              if (!name) continue;
              const key = name.toLowerCase();
              if (suppressPlug && (key.includes("adapter") || key.includes("plug converter"))) continue;
              if (seen.has(key)) continue;
              seen.add(key);
              const rawReason = String(obj?.reason || `Suggested for ${weather?.city} weather`)
                .replace(/^\[SUGGESTED\]\s*/i, "")
                .trim();
              suggestedItems.push({
                name,
                category: obj?.category || "Others",
                quantity: Math.max(1, Math.min(30, parseInt(obj?.quantity, 10) || 1)),
                bagLocation: obj?.bagLocation || "Carry-On",
                packedOutbound: false,
                packedReturn: false,
                isDoorwayCritical: false,
                isHighLossReturn: true,
                weatherReason: `${SUGGESTED_PREFIX}${rawReason}`,
              });
            }

            if (templateItems.length > 0) {
              const finalSuggested =
                suggestedItems.length > 0
                  ? suggestedItems
                  : await PackingEngineService.generateGeminiSuggestions({
                      weather,
                      durationDays,
                      tags,
                      templateItems,
                      geminiApiKey,
                    });
              return { templateItems, suggestedItems: finalSuggested, usedFallback: false };
            }
          }
        }
      } catch (_) {
        // Fall through to Stage 2 lightweight Gemini suggestions call
      }

      // Stage 2 Gemini Fallback: if the combined rule+suggestion call failed/timed out,
      // try the lightweight Gemini suggestions call (2-5 items only) before falling back to rule-based!
      try {
        const existingNames = new Set((fallbackTemplateItems || []).map((i) => String(i.name || "").trim().toLowerCase()));
        const stage2Payload = PackingEngineService.buildGeminiSuggestionRequestPayload({
          weather,
          durationDays,
          tags,
          templateItems: fallbackTemplateItems,
        });
        const stage2Result = await PackingEngineService.callGeminiWithModelCascade(stage2Payload, geminiApiKey, { fetchImpl });
        if (stage2Result?.data) {
          const stage2Suggestions = PackingEngineService.parseGeminiSuggestionResponse(stage2Result.data, {
            existingNames,
            city: weather?.city || "destination",
            suppressPlug,
          });
          if (stage2Suggestions.length > 0) {
            return { templateItems: fallbackTemplateItems, suggestedItems: stage2Suggestions, usedFallback: false };
          }
        }
      } catch (_) {
        // Both Stage 1 and Stage 2 Gemini calls failed; fall through to rule-based backup
      }
    }

    const fallbackSuggested = await PackingEngineService.generateGeminiSuggestions({
      weather,
      durationDays,
      tags,
      templateItems: fallbackTemplateItems,
      geminiApiKey: "",
    });
    return { templateItems: fallbackTemplateItems, suggestedItems: fallbackSuggested, usedFallback: true };
  }

  static async createTripObject({ destination, durationDays = 3, startDate = "", endDate = "", tags = [], customRules = [], geminiApiKey = "" }) {
    const weather = await PackingEngineService.fetchDestinationWeather(destination, durationDays, startDate, endDate);
    const mergedTags = Array.from(
      new Set([
        ...tags.map((t) => String(t).toLowerCase().trim()).filter(Boolean),
        ...(weather.isInternational ? ["international"] : []),
      ])
    );
    const { templateItems, suggestedItems, usedFallback } = await PackingEngineService.evaluateTemplateAndSuggestWithGemini({
      weather,
      durationDays,
      tags: mergedTags,
      customRules,
      geminiApiKey,
    });
    const items = [...templateItems, ...suggestedItems];
    const cleanStart = /^\d{4}-\d{2}-\d{2}$/.test(String(startDate || "").trim())
      ? String(startDate).trim()
      : new Date().toISOString().slice(0, 10);
    const daysNum = Number(durationDays) || 3;
    const cleanEnd = /^\d{4}-\d{2}-\d{2}$/.test(String(endDate || "").trim())
      ? String(endDate).trim()
      : (() => {
          const [y, m, d] = cleanStart.split("-").map(Number);
          return new Date(Date.UTC(y, m - 1, d + daysNum)).toISOString().slice(0, 10);
        })();

    const fallbackPrefix = usedFallback ? "[FALLBACK] Gemini unavailable — rule-based backup used · " : "";
    return {
      destination: weather.city,
      country: weather.country,
      startDate: cleanStart,
      endDate: cleanEnd,
      durationDays: daysNum,
      avgHighF: weather.avgHighF,
      avgLowF: weather.avgLowF,
      rainyDaysCount: weather.rainyDaysCount,
      plugTypes: weather.plugTypes,
      tripMode: "OUTBOUND",
      tagsCsv: mergedTags.join(","),
      usedFallback: Boolean(usedFallback),
      lastAgentNote: `${fallbackPrefix}Forecast ${Math.round(weather.avgLowF)}°–${Math.round(weather.avgHighF)}°F · ${templateItems.length} template items + ${suggestedItems.length} ${usedFallback ? "rule-based" : "Gemini"} suggestions`,
      items,
    };
  }

  static async handleNaturalLanguageCommand(text, state, activeTripIndex = 0) {
    const raw = String(text || "").trim();
    const lower = raw.toLowerCase();

    if (!raw) {
      return { reply: "Tell me a destination and duration (e.g. 'Atlanta for 3 days') or an item to check off.", state };
    }

    const activeTrip = state.trips[activeTripIndex] || state.trips[0];
    if (activeTrip) {
      if (lower.includes("doorway")) {
        activeTrip.tripMode = "DOORWAY";
        return { reply: `Switched ${activeTrip.destination} to 60-Second Doorway Mode.`, state, activeTripIndex };
      }
      if (lower.includes("checkout") || lower.includes("return") || lower.includes("hotel")) {
        activeTrip.tripMode = "RETURN_SWEEP";
        return { reply: `Switched ${activeTrip.destination} to Hotel Checkout Sweep Mode.`, state, activeTripIndex };
      }
      if (lower.includes("outbound") || lower.includes("pack mode")) {
        activeTrip.tripMode = "OUTBOUND";
        return { reply: `Switched ${activeTrip.destination} to Outbound Pack Mode.`, state, activeTripIndex };
      }

      if (/^(check|pack|packed|done|got|mark|uncheck)\b/i.test(lower)) {
        const shouldCheck = !lower.startsWith("uncheck");
        const matched = [];
        for (const item of activeTrip.items || []) {
          const itemNameLower = String(item.name || "").toLowerCase();
          const words = itemNameLower.split(/[^a-z0-9]+/).filter((w) => w.length >= 3);
          if (lower.includes(itemNameLower) || words.some((w) => lower.includes(w))) {
            if (activeTrip.tripMode === "RETURN_SWEEP") {
              item.packedReturn = shouldCheck;
            } else {
              item.packedOutbound = shouldCheck;
            }
            matched.push(item.name);
          }
        }
        if (matched.length > 0) {
          return {
            reply: `${shouldCheck ? "Checked off" : "Unchecked"}: ${matched.join(", ")}.`,
            state,
            activeTripIndex,
          };
        }
      }
    }

    const daysMatch = raw.match(/(\d+)\s*(?:day|days|night|nights|d)\b/i);
    const durationDays = daysMatch ? Math.max(1, Math.min(30, parseInt(daysMatch[1], 10))) : 3;

    let dest = "Atlanta";
    const inMatch = raw.match(/\b(?:in|to|for)\s+([A-Z][A-Za-z\s.-]{1,28}?)(?=\s+(?:for|with|on|next|\d|$)|$)/);
    if (inMatch && !/^(work|business|leisure|vacation|personal|carry|checked)$/i.test(inMatch[1].trim())) {
      dest = inMatch[1].trim();
    } else {
      const cleaned = raw
        .replace(/(\d+)\s*(?:day|days|night|nights|d)\b/gi, "")
        .replace(/\b(pack|packing|trip|create|plan|new|for|to|in|with|work|business|leisure|rental|car|driving|own|carry-on|checked|bag|backpack)\b/gi, " ")
        .trim();
      if (cleaned) dest = cleaned.split(/\s+/).slice(0, 3).join(" ");
    }

    const tags = [];
    if (/\b(work|business|office|conference)\b/i.test(lower)) tags.push("work");
    else tags.push("leisure");

    if (/\b(own car|personal car|driving|road trip)\b/i.test(lower)) tags.push("own_car");
    else if (/\b(no car|transit|train)\b/i.test(lower)) tags.push("no_car", "flight");
    else tags.push("rental_car", "flight");

    if (/\b(checked|suitcase)\b/i.test(lower)) tags.push("checked_bag");
    else if (/\b(backpack|personal only|shoulder)\b/i.test(lower)) tags.push("personal_only");
    else tags.push("carry_on");

    const newTrip = await PackingEngineService.createTripObject({
      destination: dest,
      durationDays,
      tags,
      customRules: state.custom_rules,
    });
    state.trips.unshift(newTrip);

    return {
      reply: `Created ${newTrip.durationDays}-day trip to ${newTrip.destination}, ${newTrip.country} (${Math.round(newTrip.avgLowF)}°–${Math.round(newTrip.avgHighF)}°F) with ${newTrip.items.length} items.`,
      state,
      activeTripIndex: 0,
    };
  }
}
