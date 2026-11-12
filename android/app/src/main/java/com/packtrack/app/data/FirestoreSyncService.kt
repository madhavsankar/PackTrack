package com.packtrack.app.data

import com.packtrack.app.BuildConfig
import com.packtrack.app.domain.TravelerTemplateCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Multi-User Cloud Firestore NoSQL Sync Service.
 *
 * Reads `FIREBASE_PROJECT_ID` from `BuildConfig.FIREBASE_PROJECT_ID` (configured in
 * git-ignored `local.properties` or environment variables) so no project IDs or
 * credentials are hardcoded in the repository.
 *
 * Document structure:
 *  - `"default"` -> `.../documents/packtrack_state/user_state`
 *  - Named profile -> `.../documents/packtrack_state/user_state_{userId}`
 */
object FirestoreSyncService {

    private val firebaseProjectId: String
        get() = BuildConfig.FIREBASE_PROJECT_ID.trim()

    @Volatile
    private var lastLocalMutationMs: Long = 0L

    fun markLocalMutation() {
        lastLocalMutationMs = System.currentTimeMillis()
    }

    /**
     * Normalizes a username or email to a clean username ID (strips `@domain` or `_gmail.com`).
     */
    fun sanitizeUserId(raw: String?): String {
        val trimmed = (raw ?: "").trim().lowercase()
        if (trimmed.isEmpty()) return TravelerTemplateCatalog.DEFAULT_USER_ID
        val withoutDomain = trimmed.substringBefore("@").removeSuffix("_gmail.com")
        val cleaned = withoutDomain.replace(Regex("[^a-z0-9_.-]"), "_").take(64)
        return cleaned.ifEmpty { TravelerTemplateCatalog.DEFAULT_USER_ID }
    }

    /**
     * Formats the internal Firestore userId back to a readable username label for UI display.
     */
    fun displayUserLabel(userId: String): String {
        return sanitizeUserId(userId)
    }

    /**
     * Checks whether a given email or username matches `BuildConfig.ALLOWED_EMAILS` (when configured).
     */
    fun isUserAllowed(raw: String?): Boolean {
        val cleaned = sanitizeUserId(raw)
        if (cleaned.isBlank() || cleaned == "default") return false
        val allowedRaw = BuildConfig.ALLOWED_EMAILS.trim()
        if (allowedRaw.isEmpty()) return true
        val allowedSet = allowedRaw.split(",")
            .flatMap { entry ->
                val c = entry.trim().lowercase()
                if (c.isEmpty()) emptyList()
                else listOf(c, c.substringBefore("@"), sanitizeUserId(c))
            }
            .toSet()
        val rawLower = (raw ?: "").trim().lowercase()
        return allowedSet.contains(rawLower) || allowedSet.contains(cleaned)
    }

    private fun docUrlForUser(userId: String): String? {
        val projectId = firebaseProjectId
        val clean = sanitizeUserId(userId)
        // Never sync the anonymous "default" profile to Cloud Firestore; only sync signed-in user accounts
        if (projectId.isBlank() || clean == "default") return null
        return "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/packtrack_state/user_state_$clean"
    }

    /**
     * Ensures a user profile has template rules in local Room SQLite (seeds generic starter rules
     * only if no cloud or local rules exist for that profile).
     */
    suspend fun ensureUserTemplateSeeded(dao: PackTrackDao, userId: String) = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val existingRules = dao.getTemplateRulesForUser(cleanUser)
        if (existingRules.isEmpty()) {
            val genericRules = TravelerTemplateCatalog.buildDefaultGenericTemplateRules(cleanUser)
            for (rule in genericRules) {
                dao.insertTemplateRule(rule)
            }
        }
    }

    /**
     * Pushes the specified user's local Room SQLite state (trips, items, and template rules)
     * to Cloud Firestore (`packtrack_state/user_state_{userId}`).
     */
    suspend fun pushLocalStateToFirestore(
        dao: PackTrackDao,
        userId: String = TravelerTemplateCatalog.DEFAULT_USER_ID,
        trips: List<TripEntity>? = null
    ): Boolean = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        try {
            markLocalMutation()
            val cleanUser = sanitizeUserId(userId)
            val docUrl = docUrlForUser(cleanUser) ?: return@withContext false
            val userTrips = trips ?: dao.getTripsForUser(cleanUser)
            val rules = dao.getTemplateRulesForUser(cleanUser)
            val tripsArray = JSONArray()
            for (trip in userTrips) {
                val items = dao.getItemsForTrip(trip.id)
                val itemsArray = JSONArray()
                for (item in items) {
                    itemsArray.put(
                        JSONObject().apply {
                            put("name", item.name)
                            put("category", item.category)
                            put("quantity", item.quantity)
                            put("bagLocation", item.bagLocation)
                            put("packedOutbound", item.packedOutbound)
                            put("packedReturn", item.packedReturn)
                            put("isDoorwayCritical", item.isDoorwayCritical)
                            put("isHighLossReturn", item.isHighLossReturn)
                            put("weatherReason", item.weatherReason)
                        }
                    )
                }
                tripsArray.put(
                    JSONObject().apply {
                        put("destination", trip.destination)
                        put("country", trip.country)
                        put("startDate", trip.startDate)
                        put("durationDays", trip.durationDays)
                        put("avgHighF", trip.avgHighF)
                        put("avgLowF", trip.avgLowF)
                        put("rainyDaysCount", trip.rainyDaysCount)
                        put("plugTypes", trip.plugTypes)
                        put("tripMode", trip.tripMode)
                        put("tagsCsv", trip.tagsCsv)
                        put("lastAgentNote", trip.lastAgentNote)
                        put("items", itemsArray)
                    }
                )
            }

            val rulesArray = JSONArray()
            for (rule in rules) {
                rulesArray.put(
                    JSONObject().apply {
                        put("moduleKey", rule.moduleKey)
                        put("name", rule.name)
                        put("category", rule.category)
                        put("scaleByDays", rule.scaleByDays)
                        put("ratioPerDay", rule.ratioPerDay)
                        put("isDoorwayCritical", rule.isDoorwayCritical)
                        put("isHighLossReturn", rule.isHighLossReturn)
                        put("bagLocation", rule.bagLocation)
                    }
                )
            }

            val statePayload = JSONObject().apply {
                put("userId", cleanUser)
                put("email", displayUserLabel(cleanUser))
                put("trips", tripsArray)
                put("custom_rules", rulesArray)
            }

            val firestoreBody = JSONObject().apply {
                put(
                    "fields",
                    JSONObject().apply {
                        put("userId", JSONObject().put("stringValue", cleanUser))
                        put("email", JSONObject().put("stringValue", displayUserLabel(cleanUser)))
                        put("payload", JSONObject().put("stringValue", statePayload.toString()))
                        put("tripCount", JSONObject().put("integerValue", userTrips.size.toString()))
                        put("templateRuleCount", JSONObject().put("integerValue", rules.size.toString()))
                        put("updatedAt", JSONObject().put("stringValue", Instant.now().toString()))
                    }
                )
            }

            val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            conn.outputStream.use { it.write(firestoreBody.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pulls state for the specified user from Cloud Firestore (`packtrack_state/user_state_{userId}`)
     * first; if no cloud rules exist yet for that profile, falls back to seeding generic rules.
     */
    suspend fun pullFromFirestoreIfNeeded(
        dao: PackTrackDao,
        userId: String = TravelerTemplateCatalog.DEFAULT_USER_ID,
        currentLocalTrips: List<TripEntity>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val docUrl = docUrlForUser(cleanUser)
        if (docUrl == null) {
            ensureUserTemplateSeeded(dao, cleanUser)
            return@withContext false
        }
        try {
            val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode !in 200..299) {
                ensureUserTemplateSeeded(dao, cleanUser)
                return@withContext false
            }

            val docText = conn.inputStream.bufferedReader().use { it.readText() }
            val docJson = JSONObject(docText)
            val payloadStr = docJson.optJSONObject("fields")
                ?.optJSONObject("payload")
                ?.optString("stringValue", "") ?: ""
            if (payloadStr.isBlank()) {
                ensureUserTemplateSeeded(dao, cleanUser)
                return@withContext false
            }

            val payload = JSONObject(payloadStr)

            // 1. Sync Template Rules for this user from Cloud Firestore (replace local rules if cloud has rules)
            val rulesArr = payload.optJSONArray("custom_rules") ?: JSONArray()
            if (rulesArr.length() > 0) {
                dao.deleteAllTemplateRulesForUser(cleanUser)
                val seenNames = mutableSetOf<String>()
                for (i in 0 until rulesArr.length()) {
                    val r = rulesArr.getJSONObject(i)
                    val name = r.optString("name", "")
                    if (name.isNotBlank() && seenNames.add(name.lowercase())) {
                        dao.insertTemplateRule(
                            TemplateRuleEntity(
                                userId = cleanUser,
                                moduleKey = r.optString("moduleKey", "always"),
                                name = name,
                                category = r.optString("category", "On Me"),
                                scaleByDays = r.optBoolean("scaleByDays", false),
                                ratioPerDay = r.optDouble("ratioPerDay", 1.0),
                                isDoorwayCritical = r.optBoolean("isDoorwayCritical", false),
                                isHighLossReturn = r.optBoolean("isHighLossReturn", true),
                                bagLocation = r.optString("bagLocation", "Carry-On")
                            )
                        )
                    }
                }
            }

            // If cloud had no rules and local DB is still empty, seed generic rules
            ensureUserTemplateSeeded(dao, cleanUser)

            // 2. Merge or update Cloud Trips for this user
            val localTrips = currentLocalTrips ?: dao.getTripsForUser(cleanUser)
            val unclaimedLocalTrips = localTrips.toMutableList()
            val recentlyMutatedLocally = (System.currentTimeMillis() - lastLocalMutationMs) < 20_000L
            val tripsArr = payload.optJSONArray("trips") ?: JSONArray()
            for (i in 0 until tripsArr.length()) {
                val t = tripsArr.getJSONObject(i)
                val dest = t.optString("destination", "")
                val days = t.optInt("durationDays", 5)
                if (dest.isBlank()) continue

                val existingTrip = unclaimedLocalTrips.firstOrNull {
                    it.destination.equals(dest, ignoreCase = true) && it.durationDays == days
                } ?: unclaimedLocalTrips.firstOrNull {
                    it.destination.equals(dest, ignoreCase = true)
                }
                if (existingTrip != null) {
                    unclaimedLocalTrips.remove(existingTrip)
                }
                val itemsArr = t.optJSONArray("items") ?: JSONArray()

                if (existingTrip == null) {
                    if (recentlyMutatedLocally) continue
                    val newTripId = dao.insertTrip(
                        TripEntity(
                            userId = cleanUser,
                            destination = dest,
                            country = t.optString("country", "United States"),
                            startDate = t.optString("startDate", java.time.LocalDate.now().toString()),
                            durationDays = days,
                            avgHighF = t.optDouble("avgHighF", 64.0),
                            avgLowF = t.optDouble("avgLowF", 49.0),
                            rainyDaysCount = t.optInt("rainyDaysCount", 1),
                            plugTypes = t.optString("plugTypes", "Type A/B"),
                            tripMode = t.optString("tripMode", "OUTBOUND"),
                            tagsCsv = t.optString("tagsCsv", "work,rental_car"),
                            lastAgentNote = t.optString("lastAgentNote", "Synced from Cloud Firestore ($cleanUser)")
                        )
                    )
                    val itemEntities = mutableListOf<PackingItemEntity>()
                    for (j in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.getJSONObject(j)
                        itemEntities.add(
                            PackingItemEntity(
                                tripId = newTripId,
                                name = itemObj.optString("name", "Item"),
                                category = itemObj.optString("category", "Basics (Leave-the-House)"),
                                quantity = itemObj.optInt("quantity", 1),
                                bagLocation = itemObj.optString("bagLocation", "Carry-On"),
                                packedOutbound = itemObj.optBoolean("packedOutbound", false),
                                packedReturn = itemObj.optBoolean("packedReturn", false),
                                isDoorwayCritical = itemObj.optBoolean("isDoorwayCritical", false),
                                isHighLossReturn = itemObj.optBoolean("isHighLossReturn", false),
                                weatherReason = itemObj.optString("weatherReason", "")
                            )
                        )
                    }
                    if (itemEntities.isNotEmpty()) {
                        dao.insertItems(itemEntities)
                    }
                } else {
                    val cloudStart = t.optString("startDate", "")
                    val localNeedsStart = existingTrip.startDate.isBlank() || existingTrip.startDate == "Saved Trip"
                    if (cloudStart.isNotBlank() && cloudStart != existingTrip.startDate && (localNeedsStart || !recentlyMutatedLocally)) {
                        dao.updateTripStartDate(existingTrip.id, cloudStart)
                    }
                    val cloudMode = t.optString("tripMode", existingTrip.tripMode)
                    if (cloudMode != existingTrip.tripMode && !recentlyMutatedLocally) {
                        dao.updateTripMode(existingTrip.id, cloudMode)
                    }
                    val localItems = dao.getItemsForTrip(existingTrip.id)
                    val localItemByName = localItems.associateBy { it.name.lowercase() }
                    val newItems = mutableListOf<PackingItemEntity>()
                    for (j in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.getJSONObject(j)
                        val iName = itemObj.optString("name", "")
                        if (iName.isBlank()) continue
                        val localItem = localItemByName[iName.lowercase()]
                        val cloudOut = itemObj.optBoolean("packedOutbound", false)
                        val cloudRet = itemObj.optBoolean("packedReturn", false)
                        if (localItem != null) {
                            if (localItem.packedOutbound != cloudOut) {
                                dao.setItemPackedOutbound(localItem.id, cloudOut)
                            }
                            if (localItem.packedReturn != cloudRet) {
                                dao.setItemPackedReturn(localItem.id, cloudRet)
                            }
                        } else {
                            newItems.add(
                                PackingItemEntity(
                                    tripId = existingTrip.id,
                                    name = iName,
                                    category = itemObj.optString("category", "Basics (Leave-the-House)"),
                                    quantity = itemObj.optInt("quantity", 1),
                                    bagLocation = itemObj.optString("bagLocation", "Carry-On"),
                                    packedOutbound = cloudOut,
                                    packedReturn = cloudRet,
                                    isDoorwayCritical = itemObj.optBoolean("isDoorwayCritical", false),
                                    isHighLossReturn = itemObj.optBoolean("isHighLossReturn", false),
                                    weatherReason = itemObj.optString("weatherReason", "")
                                )
                            )
                        }
                    }
                    if (newItems.isNotEmpty()) {
                        dao.insertItems(newItems)
                    }
                }
            }
            true
        } catch (_: Exception) {
            ensureUserTemplateSeeded(dao, cleanUser)
            false
        }
    }
}
