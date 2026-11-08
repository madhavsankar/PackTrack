/**
 * Multi-User Cloud Firestore Repository for PackTrack (`packtrack_state/user_state_{userId}`).
 *
 * Anonymous `"default"` profile never reads or writes to Cloud Firestore so public
 * visitors do not pollute the owner's database.
 */

export const GENERIC_STARTER_RULES = [
  { moduleKey: "Always", name: "Phones", category: "On Me", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Always", name: "Wallet", category: "On Me", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Always", name: "License", category: "On Me", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Always", name: "Watch", category: "On Me", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Always", name: "Phone charger", category: "Electronics", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Always", name: "Earbuds / AirPods", category: "Electronics", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Always", name: "Hotel docs", category: "Docs", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: false, bagLocation: "Personal" },
  { moduleKey: "Always", name: "Lip balm", category: "Toiletry", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "Sunscreen", category: "Toiletry", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "Moisturizer", category: "Toiletry", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "Jeans", category: "Clothes", scaleByDays: true, ratioPerDay: 0.35, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "T-shirts", category: "Clothes", scaleByDays: true, ratioPerDay: 1.0, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "Socks", category: "Clothes", scaleByDays: true, ratioPerDay: 1.0, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "Underwear", category: "Clothes", scaleByDays: true, ratioPerDay: 1.0, isDoorwayCritical: false, isHighLossReturn: false, bagLocation: "Carry-On" },
  { moduleKey: "Always", name: "Sunglasses", category: "Clothes", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Only when working", name: "Laptop", category: "Electronics", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Only when working", name: "Laptop charger", category: "Electronics", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Only for international trips", name: "Passport", category: "Docs", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
  { moduleKey: "Only when flying", name: "Power bank", category: "Electronics", scaleByDays: false, ratioPerDay: 1.0, isDoorwayCritical: true, isHighLossReturn: true, bagLocation: "Personal" },
];

export class FirestoreRepository {
  constructor(env) {
    this.projectId = (env?.FIREBASE_PROJECT_ID || "").trim();
  }

  static sanitizeUserId(raw) {
    const trimmed = String(raw || "").trim().toLowerCase();
    if (!trimmed) return "default";
    const withoutDomain = trimmed.split("@")[0].replace(/_gmail\.com$/, "");
    const cleaned = withoutDomain.replace(/[^a-z0-9_.-]/g, "_").slice(0, 64);
    return cleaned || "default";
  }

  static displayUserLabel(userId) {
    return FirestoreRepository.sanitizeUserId(userId);
  }

  docUrlForUser(userId) {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    if (!this.projectId || clean === "default") return null;
    return `https://firestore.googleapis.com/v1/projects/${this.projectId}/databases/(default)/documents/packtrack_state/user_state_${clean}`;
  }

  defaultState(userId = "default") {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    return {
      userId: clean,
      email: FirestoreRepository.displayUserLabel(clean),
      trips: [],
      custom_rules: GENERIC_STARTER_RULES.map((r) => ({ ...r })),
    };
  }

  async getUserState(userId = "default") {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    const url = this.docUrlForUser(clean);
    if (!url) {
      return this.defaultState(clean);
    }

    try {
      const resp = await fetch(url, { method: "GET" });
      if (!resp.ok) {
        return this.defaultState(clean);
      }
      const doc = await resp.json();
      const payloadStr = doc?.fields?.payload?.stringValue;
      if (!payloadStr) {
        return this.defaultState(clean);
      }
      const parsed = JSON.parse(payloadStr);
      const trips = Array.isArray(parsed.trips) ? parsed.trips : [];
      const customRules =
        Array.isArray(parsed.custom_rules) && parsed.custom_rules.length > 0
          ? parsed.custom_rules
          : GENERIC_STARTER_RULES.map((r) => ({ ...r }));

      return {
        userId: clean,
        email: parsed.email || FirestoreRepository.displayUserLabel(clean),
        trips,
        custom_rules: customRules,
      };
    } catch (_) {
      return this.defaultState(clean);
    }
  }

  async saveUserState(userId, state) {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    const url = this.docUrlForUser(clean);
    if (!url) {
      return false;
    }

    const trips = Array.isArray(state.trips) ? state.trips : [];
    const customRules = Array.isArray(state.custom_rules) ? state.custom_rules : [];
    const payloadObj = {
      userId: clean,
      email: state.email || FirestoreRepository.displayUserLabel(clean),
      trips,
      custom_rules: customRules,
    };

    const firestoreBody = {
      fields: {
        userId: { stringValue: clean },
        email: { stringValue: payloadObj.email },
        payload: { stringValue: JSON.stringify(payloadObj) },
        tripCount: { integerValue: String(trips.length) },
        templateRuleCount: { integerValue: String(customRules.length) },
        updatedAt: { stringValue: new Date().toISOString() },
      },
    };

    try {
      const resp = await fetch(url, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(firestoreBody),
      });
      return resp.ok;
    } catch (_) {
      return false;
    }
  }
}

