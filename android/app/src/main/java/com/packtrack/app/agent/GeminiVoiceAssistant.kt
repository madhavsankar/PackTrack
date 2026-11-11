package com.packtrack.app.agent

import com.packtrack.app.data.FirestoreSyncService
import com.packtrack.app.data.OpenMeteoWeatherService
import com.packtrack.app.data.PackTrackDao
import com.packtrack.app.data.PackingItemEntity
import com.packtrack.app.data.TemplateRuleEntity
import com.packtrack.app.data.TripEntity
import com.packtrack.app.domain.BagLocation
import com.packtrack.app.domain.CarTransportMode
import com.packtrack.app.domain.ChecklistCalculator
import com.packtrack.app.domain.LuggageSpace
import com.packtrack.app.domain.PackingCategory
import com.packtrack.app.domain.RuleCondition
import com.packtrack.app.domain.TravelerTemplateCatalog
import com.packtrack.app.domain.TripMode
import com.packtrack.app.domain.WeatherForecast
import com.packtrack.app.ui.theme.PackTrackThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

/**
 * Conversational & Voice Orchestrator for PackTrack.
 *
 * Delegates:
 *  - Weather lookups to [OpenMeteoWeatherService]
 *  - Template catalog & static items to [TravelerTemplateCatalog]
 *  - Deterministic checklist synthesis to [ChecklistCalculator]
 *  - Persistence to [PackTrackDao] + [FirestoreSyncService]
 */
class GeminiVoiceAssistant(
    private val dao: PackTrackDao,
    var geminiApiKey: String,
    var currentUserId: String = TravelerTemplateCatalog.DEFAULT_USER_ID
) {
    companion object {
        private const val MODEL = "gemini-3.8-flash"
        private const val MODELS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val FALLBACK_TAG = "[FALLBACK]"

        val DEFAULT_MODEL_CASCADE: List<String> = listOf(
            "gemini-flash-latest",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3-flash-preview",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.1-flash-lite-preview",
            "gemini-flash-lite-latest",
            "gemini-3.1-pro-preview",
            "gemini-pro-latest"
        )

        val EMERGENCY_LAST_RESORT_MODELS: List<String> = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )

        @Volatile
        private var cachedDiscoveredModels: List<String>? = null

        @Volatile
        private var cachedCascadeAtMs: Long = 0L

        @Volatile
        private var lastWorkingModel: String? = null

        fun extractCleanJson(rawText: String): String? {
            val cleaned = rawText
                .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
            if (cleaned.isEmpty()) return null
            fun hasBalancedJsonStructure(s: String): Boolean {
                val t = s.trim()
                if (t.length < 2) return false
                val first = t.first()
                val last = t.last()
                if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) return false
                var depth = 0
                var inString = false
                var escaped = false
                for (ch in t) {
                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (ch == '\\' && inString) {
                        escaped = true
                        continue
                    }
                    if (ch == '"') {
                        inString = !inString
                        continue
                    }
                    if (!inString) {
                        when (ch) {
                            '{', '[' -> depth++
                            '}', ']' -> {
                                depth--
                                if (depth < 0) return false
                            }
                        }
                    }
                }
                return !inString && depth == 0
            }
            fun isValidJson(candidate: String): Boolean {
                if (!hasBalancedJsonStructure(candidate)) return false
                return try {
                    if (candidate.startsWith("{")) {
                        JSONObject(candidate)
                        true
                    } else if (candidate.startsWith("[")) {
                        JSONArray(candidate)
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }
            if (isValidJson(cleaned)) return cleaned
            val objStart = cleaned.indexOf('{')
            val objEnd = cleaned.lastIndexOf('}')
            if (objStart != -1 && objEnd > objStart) {
                val sub = cleaned.substring(objStart, objEnd + 1)
                if (isValidJson(sub)) return sub
            }
            val arrStart = cleaned.indexOf('[')
            val arrEnd = cleaned.lastIndexOf(']')
            if (arrStart != -1 && arrEnd > arrStart) {
                val sub = cleaned.substring(arrStart, arrEnd + 1)
                if (isValidJson(sub)) return sub
            }
            return null
        }

        /**
         * Multi-stage model classifier and scorer for `/v1beta/models` entries:
         *  1. Rejects non-Gemini families (`gemma-*`, `lyria-*`, `imagen-*`, `veo-*`, `aqa`, `text-embedding-*`)
         *  2. Rejects non-text / specialized media models that still support `generateContent`
         *     (`-image`, `nano-banana`, `omni`, `transcribe`, `tts`, `live`, `translate`, `robotics`, `computer-use`)
         *  3. Rejects legacy/deprecated `< 3.0` generations (`2.5`, `2.0`, `1.5`, `1.0`)
         *  4. Ranks by `(Tier Class -> Release Stability -> Major.Minor Version)`:
         *     - Tier 3 (Fast Structured Text Primary): `flash` (non-lite)
         *     - Tier 2 (Fast Structured Text Secondary): `flash-lite`
         *     - Tier 1 (Heavy Reasoning Fallback): `pro`, `argon`, `ultra`
         */
        fun scoreGeminiModelId(rawId: String, description: String = "", outputTokenLimit: Int = 0): Int {
            val id = rawId.removePrefix("models/").trim().lowercase()
            if (!id.startsWith("gemini-")) return -1

            // Stage 1: Exclude specialized media/audio/agent/embedding models (many of which still list generateContent)
            val excludedPattern = Regex(
                "image|banana|omni|transcribe|tts|audio|video|music|lyria|veo|imagen|embedding|aqa|learnlm|robotics|computer-use|deep-research|antigravity|translate|thinking|live|vision|customtools"
            )
            if (excludedPattern.containsMatchIn(id)) return -1

            // Stage 2: Check description / outputTokenLimit if metadata was provided
            val descLower = description.lowercase()
            if (descLower.isNotEmpty() && Regex("shut down|deprecated|text-to-speech|speech-to-text|image generation|video generation").containsMatchIn(descLower)) {
                return -1
            }
            if (outputTokenLimit in 1 until 2048) return -1

            // Stage 3: Identify tier class (must be an explicit general text tier: flash, flash-lite, pro, or argon)
            val tierScore = when {
                id.contains("flash-lite") || id.contains("lite") -> 20_000
                id.contains("flash") -> 30_000
                id.contains("pro") || id.contains("argon") || id.contains("ultra") -> 10_000
                else -> return -1
            }

            // Stage 4: Parse version or rolling `-latest` alias; reject legacy `< 3.0` models
            val verMatch = Regex("""^gemini-(\d+)(?:\.(\d+))?-(?:flash-lite|flash|pro|argon|ultra)(?:$|-[a-z0-9-]+$)""").find(id)
            val versionScore = if (verMatch != null) {
                val major = verMatch.groupValues[1].toIntOrNull() ?: 0
                val minor = verMatch.groupValues[2].toIntOrNull() ?: 0
                if (major < 3) return -1 // Never select deprecated/legacy 1.x or 2.x models
                major * 100 + minor * 10
            } else if (id == "gemini-flash-latest" || id == "gemini-flash-lite-latest" || id == "gemini-pro-latest") {
                375
            } else {
                return -1
            }

            // Stage 5: Stability weight (Stable GA > Rolling `-latest` > Pinned `-001` > `-preview` > `-exp`)
            val stabilityScore = when {
                id.contains("exp") || id.contains("experimental") -> 1000
                id.contains("preview") -> 2000
                id.endsWith("-latest") -> 4500
                Regex("""^gemini-\d+(?:\.\d+)?-(?:flash-lite|flash|pro|argon|ultra)$""").matches(id) -> 5000
                else -> 4000
            }

            return tierScore + stabilityScore + versionScore
        }

        /**
         * Ranks discovered model IDs from best/newest to fallback and merges with [DEFAULT_MODEL_CASCADE].
         */
        fun rankModelIds(discoveredIds: List<String>, preferredWorkingModel: String? = lastWorkingModel): List<String> {
            val sortedDiscovered = discoveredIds
                .map { it.removePrefix("models/").trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { id ->
                    val s = scoreGeminiModelId(id)
                    if (s > 0) id to s else null
                }
                .sortedByDescending { it.second }
                .map { it.first }

            val result = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            fun addUnique(modelId: String?) {
                val clean = (modelId ?: "").removePrefix("models/").trim()
                if (clean.isNotEmpty() && seen.add(clean)) {
                    result.add(clean)
                }
            }

            addUnique(preferredWorkingModel)
            sortedDiscovered.forEach(::addUnique)
            DEFAULT_MODEL_CASCADE.forEach(::addUnique)
            return result
        }
    }

    /**
     * Re-fetches live Open-Meteo weather for an existing saved trip, updates weather stats,
     * and ensures dynamic Gemini weather suggestions are present at the end of the checklist.
     */
    suspend fun refreshWeatherAndUpdateTrip(tripId: Long, newDaysOverride: Int? = null): String = withContext(Dispatchers.IO) {
        val trip = dao.getTripById(tripId) ?: return@withContext "Trip not found."
        val days = newDaysOverride ?: trip.durationDays
        val forecast = OpenMeteoWeatherService.fetchWeather(trip.destination, days, trip.startDate)

        val existingItems = dao.getItemsForTrip(tripId)
        val activeItems = existingItems.filter { !ChecklistCalculator.isSuggested(it) }
        val existingSuggestions = existingItems.filter { ChecklistCalculator.isSuggested(it) }
        val changes = mutableListOf<String>()

        if (days != trip.durationDays) {
            for (item in activeItems) {
                when (item.name) {
                    "T-Shirts", "Underwear", "Socks" -> {
                        dao.updateItemQuantity(item.id, days + 1, "$days days + 1 spare")
                    }
                    "Jeans" -> {
                        dao.updateItemQuantity(item.id, maxOf(2, ceil(days / 3.0).toInt()), "1 pair per 3 days")
                    }
                }
            }
            changes.add("scaled clothes to $days days")
        }

        if (existingSuggestions.isEmpty()) {
            val tags = trip.tagsCsv.split(",").map { it.trim().lowercase() }
            val isWork = tags.contains("work")
            val carMode = when {
                tags.contains("own_car") || tags.contains("personal_car") -> CarTransportMode.OWN_CAR
                tags.contains("no_car") -> CarTransportMode.NO_CAR
                else -> CarTransportMode.RENTAL_CAR
            }
            val luggageSpace = when {
                tags.contains("two_checked_bags") -> LuggageSpace.TWO_CHECKED_BAGS
                tags.contains("checked_bag") -> LuggageSpace.CHECKED_BAG
                tags.contains("personal_only") -> LuggageSpace.PERSONAL_ONLY
                else -> LuggageSpace.CARRY_ON
            }
            val newSuggestions = generateWeatherSuggestionsWithGemini(
                tripId = tripId,
                days = days,
                isWork = isWork,
                carMode = carMode,
                luggageSpace = luggageSpace,
                forecast = forecast,
                templateItems = activeItems
            )
            if (newSuggestions.isNotEmpty()) {
                dao.insertItems(newSuggestions)
                changes.add("${newSuggestions.size} Gemini weather suggestion(s)")
            }
        }

        val hadFallback = trip.lastAgentNote.contains(FALLBACK_TAG)
        val fallbackPrefix = if (hadFallback) "$FALLBACK_TAG Gemini unavailable — rule-based backup used • " else ""
        val summary = if (changes.isEmpty()) {
            "${fallbackPrefix}Refreshed ${trip.destination} weather (${forecast.avgLowF.toInt()}°F–${forecast.avgHighF.toInt()}°F, ${forecast.rainyDaysCount} rainy days)."
        } else {
            "${fallbackPrefix}Updated ${trip.destination} (${forecast.avgLowF.toInt()}°F–${forecast.avgHighF.toInt()}°F, ${forecast.rainyDaysCount} rainy days): ${changes.joinToString(", ")}."
        }

        dao.updateTripWeatherAndDuration(
            tripId = tripId,
            highF = forecast.avgHighF,
            lowF = forecast.avgLowF,
            rainDays = forecast.rainyDaysCount,
            days = days,
            note = summary
        )
        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
        summary
    }

    /**
     * Recreates a trip's checklist from the user's latest conditional template first,
     * and appends dynamic Gemini weather suggestions at the end (preserving checkmarks on active items).
     * Accepts optional overrides for dates, duration, purpose, vehicle mode, and luggage space so the user
     * can edit any aspect of an existing trip while keeping the rest unchanged.
     */
    suspend fun recreateTripChecklist(
        tripId: Long,
        newStartDate: String? = null,
        newEndDate: String? = null,
        newDays: Int? = null,
        newIsWork: Boolean? = null,
        newCarMode: CarTransportMode? = null,
        newLuggageSpace: LuggageSpace? = null
    ): String = withContext(Dispatchers.IO) {
        val trip = dao.getTripById(tripId) ?: return@withContext "Trip not found."
        val cleanUser = FirestoreSyncService.sanitizeUserId(currentUserId)
        FirestoreSyncService.pullFromFirestoreIfNeeded(dao, cleanUser)

        val parsedTags = com.packtrack.app.domain.TripDateUtils.parseTripTags(trip.tagsCsv)
        val effectiveStartDate = newStartDate?.trim()?.takeIf { it.isNotEmpty() } ?: trip.startDate
        val effectiveDays = (
            newDays
                ?: if (!newStartDate.isNullOrBlank() && !newEndDate.isNullOrBlank()) {
                    com.packtrack.app.domain.TripDateUtils.computeDurationDays(
                        newStartDate,
                        newEndDate,
                        trip.durationDays
                    )
                } else {
                    trip.durationDays
                }
            ).coerceIn(1, 30)
        val isWork = newIsWork ?: parsedTags.isWork
        val carMode = newCarMode ?: parsedTags.carMode
        val luggageSpace = if (carMode == CarTransportMode.OWN_CAR) {
            LuggageSpace.TWO_CHECKED_BAGS
        } else {
            newLuggageSpace ?: parsedTags.luggageSpace
        }

        val forecast = OpenMeteoWeatherService.fetchWeather(
            trip.destination,
            effectiveDays,
            effectiveStartDate,
            newEndDate
        )
        val updatedTagsCsv = com.packtrack.app.domain.TripDateUtils.buildTagsCsv(
            isWork = isWork,
            carMode = carMode,
            luggageSpace = luggageSpace,
            isInternational = forecast.isInternational
        )

        val existingItems = dao.getItemsForTrip(tripId)
        val existingActiveByLowerName = existingItems
            .filter { !ChecklistCalculator.isSuggested(it) }
            .associateBy { it.name.trim().lowercase() }

        val customRules = dao.getTemplateRulesForUser(cleanUser)
        val (rawTemplateItems, suggestedItems, usedFallback) = evaluateTemplateAndSuggestWithGemini(
            tripId = tripId,
            days = effectiveDays,
            isWork = isWork,
            carMode = carMode,
            luggageSpace = luggageSpace,
            forecast = forecast,
            customRules = customRules
        )
        val templateItems = rawTemplateItems.map { newItem ->
            val prev = existingActiveByLowerName[newItem.name.trim().lowercase()]
            if (prev != null) {
                newItem.copy(
                    packedOutbound = prev.packedOutbound,
                    packedReturn = prev.packedReturn
                )
            } else {
                newItem
            }
        }

        val finalItems = templateItems + suggestedItems

        dao.deleteAllItemsForTrip(tripId)
        dao.insertItems(finalItems)

        val summary = if (usedFallback) {
            "$FALLBACK_TAG Gemini is unavailable — rule-based backup used to regenerate ${trip.destination} (${templateItems.size} template items + ${suggestedItems.size} weather suggestions)"
        } else {
            "Recreated ${trip.destination} checklist with Gemini (${templateItems.size} template items + ${suggestedItems.size} Gemini suggestions)"
        }
        dao.updateTripDetailsAndWeather(
            tripId = tripId,
            startDate = effectiveStartDate,
            days = effectiveDays,
            tagsCsv = updatedTagsCsv,
            highF = forecast.avgHighF,
            lowF = forecast.avgLowF,
            rainDays = forecast.rainyDaysCount,
            note = summary
        )
        FirestoreSyncService.pushLocalStateToFirestore(dao, cleanUser)
        summary
    }

    suspend fun handleVoiceTurn(userSpeech: String, activeTripId: Long?): String = withContext(Dispatchers.IO) {
        if (geminiApiKey.isNotBlank()) {
            try {
                return@withContext callGeminiInteractionsApi(userSpeech, activeTripId)
            } catch (_: Exception) {
                // Fall back to deterministic on-device voice parser if offline or key invalid
            }
        }
        handleLocalSmartVoiceTurn(userSpeech, activeTripId)
    }

    suspend fun fetchLiveForecast(
        destinationQuery: String,
        days: Int,
        startDate: String? = null,
        endDate: String? = null
    ): WeatherForecast = withContext(Dispatchers.IO) {
        OpenMeteoWeatherService.fetchWeather(destinationQuery, days, startDate, endDate)
    }

    suspend fun createAndSaveTrip(
        destinationQuery: String,
        days: Int,
        isWork: Boolean,
        carMode: CarTransportMode,
        luggageSpace: LuggageSpace = LuggageSpace.CARRY_ON,
        startDate: String = java.time.LocalDate.now().toString(),
        endDate: String? = null
    ): Pair<Long, String> = withContext(Dispatchers.IO) {
        val cleanUser = FirestoreSyncService.sanitizeUserId(currentUserId)
        FirestoreSyncService.pullFromFirestoreIfNeeded(dao, cleanUser)
        val forecast = OpenMeteoWeatherService.fetchWeather(destinationQuery, days, startDate, endDate)
        val note = "${forecast.city} ($days days): ${forecast.avgLowF.toInt()}°F–${forecast.avgHighF.toInt()}°F, " +
            "${forecast.rainyDaysCount} rainy days • ${carMode.label} • ${luggageSpace.label}"

        val tripId = dao.insertTrip(
            TripEntity(
                userId = cleanUser,
                destination = forecast.city,
                country = forecast.country,
                startDate = startDate,
                durationDays = days,
                avgHighF = forecast.avgHighF,
                avgLowF = forecast.avgLowF,
                rainyDaysCount = forecast.rainyDaysCount,
                plugTypes = forecast.plugTypes,
                tagsCsv = listOfNotNull(
                    if (isWork) "work" else "leisure",
                    carMode.tag,
                    luggageSpace.tag,
                    if (forecast.isInternational) "international" else null
                ).joinToString(","),
                lastAgentNote = note
            )
        )
        val customRules = dao.getTemplateRulesForUser(cleanUser)
        val (templateItems, suggestedItems, usedFallback) = evaluateTemplateAndSuggestWithGemini(
            tripId = tripId,
            days = days,
            isWork = isWork,
            carMode = carMode,
            luggageSpace = luggageSpace,
            forecast = forecast,
            customRules = customRules
        )

        val finalItems = templateItems + suggestedItems
        val finalNote = if (usedFallback) {
            "$FALLBACK_TAG Gemini unavailable — rule-based backup used • $note"
        } else {
            note
        }
        dao.updateTripWeatherAndDuration(
            tripId = tripId,
            highF = forecast.avgHighF,
            lowF = forecast.avgLowF,
            rainDays = forecast.rainyDaysCount,
            days = days,
            note = finalNote
        )

        dao.insertItems(finalItems)
        FirestoreSyncService.pushLocalStateToFirestore(dao, cleanUser)

        val reply = if (usedFallback) {
            "Gemini is unavailable — generated your $days-day ${forecast.city} checklist using rule-based backup (${templateItems.size} template items + ${suggestedItems.size} weather suggestions)."
        } else {
            "Created your $days-day ${forecast.city} checklist (${forecast.avgLowF.toInt()}°F–${forecast.avgHighF.toInt()}°F, " +
                "${forecast.rainyDaysCount} rainy days, ${templateItems.size} template items + ${suggestedItems.size} Gemini suggestions)!"
        }
        tripId to reply
    }

    /**
     * Evaluates the user's Default Template rules (including arbitrary natural-language conditions like
     * "Only add this when there is at least one rainy day and if trip is for more than 5 days and only when we go to India")
     * AND generates 2 to 5 dynamic weather & trip suggestions in a single Structured-Output Gemini call.
     * Falls back to [ChecklistCalculator] when offline or if no API key is configured, returning `usedFallback = true`.
     */
    private fun evaluateTemplateAndSuggestWithGemini(
        tripId: Long,
        days: Int,
        isWork: Boolean,
        carMode: CarTransportMode,
        luggageSpace: LuggageSpace,
        forecast: WeatherForecast,
        customRules: List<TemplateRuleEntity>
    ): Triple<List<PackingItemEntity>, List<PackingItemEntity>, Boolean> {
        val fallbackTemplateItems = ChecklistCalculator.buildChecklistForTrip(
            tripId = tripId,
            days = days,
            isWork = isWork,
            carMode = carMode,
            luggageSpace = luggageSpace,
            forecast = forecast,
            customTemplateRules = customRules
        )

        if (geminiApiKey.isNotBlank()) {
            try {
                val dailyWeatherText = if (forecast.dailyBreakdown.isNotEmpty()) {
                    forecast.dailyBreakdown.joinToString("\n") { "  - $it" }
                } else {
                    "  - Average ${forecast.avgLowF.toInt()}°F low to ${forecast.avgHighF.toInt()}°F high, ${forecast.rainyDaysCount} rainy day(s)"
                }

                val effectiveDays = when {
                    carMode == CarTransportMode.OWN_CAR || forecast.isInternational ||
                        luggageSpace == LuggageSpace.CHECKED_BAG || luggageSpace == LuggageSpace.TWO_CHECKED_BAGS -> days
                    luggageSpace == LuggageSpace.PERSONAL_ONLY -> minOf(days, 3)
                    else -> minOf(days, 6)
                }

                val rulesText = customRules.joinToString("\n") { r ->
                    val scaleDesc = if (r.scaleByDays) "scale_by_days=true (ratio=${r.ratioPerDay}/day, effectiveDays=$effectiveDays)" else "fixed_qty=1"
                    "  - Item: \"${r.name}\" | Category: \"${r.category}\" | Bag: \"${r.bagLocation}\" | Condition: \"${r.moduleKey}\" | Scaling: $scaleDesc"
                }

                val prompt = """
                    You are PackTrack's intelligent packing rule evaluator and weather advisor.

                    TASK 1 (`templateItems` — Evaluate User's Default Template Rules):
                    Evaluate EVERY item in the user's Default Template below against the TRIP DETAILS and DAY-BY-DAY WEATHER FORECAST.
                    - Include an item in `templateItems` IF AND ONLY IF its `Condition` is satisfied for this trip (for example: "Always" is always included; "Only when working" or "if work is expected to be done" is included only when Purpose is Work; complex natural-language conditions like "Only add this when there is at least one rainy day and if trip is for more than 5 days and only when we go to India" are included only when ALL parts of that condition hold true for this trip).
                    - If `scale_by_days=true`, compute `quantity = ceil(effectiveDays * ratio)`. Otherwise `quantity = 1`. Keep `reason` concise (max 8 words) for templateItems.

                    TASK 2 (`suggestedItems` — Dynamic Weather & Trip Suggestions):
                    Suggest 2 to 5 additional practical items or preparations tailored to the day-by-day forecast, destination, and vehicle mode that are NOT already in `templateItems` (e.g. raincoat/umbrella citing rainy days, snow tires/ice scraper if freezing/snowing while driving, sun hat/sunscreen on high-UV/hot days, windbreaker on windy days${if (TravelerTemplateCatalog.isPlugInfoNeeded(forecast.country, forecast.countryCode, forecast.plugTypes)) ", or ${forecast.plugTypes} power adapter" else "; do NOT suggest a power/plug adapter for USA or India trips"}).

                    TRIP DETAILS:
                    - Destination City: ${forecast.city}
                    - Destination Country: ${forecast.country} (Country Code: ${forecast.countryCode}, International: ${forecast.isInternational}${if (TravelerTemplateCatalog.isPlugInfoNeeded(forecast.country, forecast.countryCode, forecast.plugTypes)) ", Plug: ${forecast.plugTypes}" else ""})
                    - Duration: $days days (Effective clothing scaling days: $effectiveDays)
                    - Transport / Vehicle: ${carMode.label} (Driving Personal Car: ${carMode == CarTransportMode.OWN_CAR}, Rental Car: ${carMode == CarTransportMode.RENTAL_CAR}, Flying: ${carMode != CarTransportMode.OWN_CAR})
                    - Luggage Space: ${luggageSpace.label}
                    - Purpose: ${if (isWork) "Work / Business (isWork=true)" else "Leisure / Personal (isWork=false)"}

                    DAY-BY-DAY WEATHER FORECAST (Avg Low: ${forecast.avgLowF.toInt()}°F, Avg High: ${forecast.avgHighF.toInt()}°F, Rainy Days: ${forecast.rainyDaysCount}):
                    $dailyWeatherText

                    USER'S DEFAULT TEMPLATE RULES:
                    $rulesText
                """.trimIndent()

                val allowedCategories = PackTrackThemeState.allCategories(customRules.map { it.category })
                val rawText = callGeminiEvaluateAndSuggest(prompt, allowedCategories)
                val cleanJson = extractCleanJson(rawText) ?: rawText
                val rootObj = JSONObject(cleanJson)
                val ruleByLowerName = customRules.associateBy { it.name.trim().lowercase() }

                val parsedTemplate = mutableListOf<PackingItemEntity>()
                val seenNames = mutableSetOf<String>()
                val templateArr = rootObj.optJSONArray("templateItems") ?: JSONArray()
                for (i in 0 until templateArr.length()) {
                    val obj = templateArr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    val key = name.lowercase()
                    if (!seenNames.add(key)) continue
                    val matchedRule = ruleByLowerName[key]
                    val category = PackingCategory.canonicalize(
                        obj.optString("category", matchedRule?.category ?: "Others")
                    )
                    val qty = obj.optInt("quantity", 1).coerceAtLeast(1)
                    val bag = BagLocation.fromLabel(
                        obj.optString("bagLocation", matchedRule?.bagLocation ?: BagLocation.CARRY_ON.label)
                    ).label
                    val reason = ChecklistCalculator.stripSuggestedPrefix(obj.optString("reason", ""))
                    parsedTemplate.add(
                        PackingItemEntity(
                            tripId = tripId,
                            name = matchedRule?.name ?: name,
                            category = category,
                            quantity = qty,
                            bagLocation = bag,
                            isDoorwayCritical = matchedRule?.isDoorwayCritical ?: (category == "On Me" || category == "Docs"),
                            isHighLossReturn = matchedRule?.isHighLossReturn ?: true,
                            weatherReason = reason
                        )
                    )
                }

                // Ensure any "Always" template rules are never accidentally dropped
                for (fallbackItem in fallbackTemplateItems) {
                    val key = fallbackItem.name.trim().lowercase()
                    val rule = ruleByLowerName[key]
                    val isAlways = rule == null || rule.moduleKey.isBlank() || rule.moduleKey.equals("Always", ignoreCase = true)
                    if (isAlways && seenNames.add(key)) {
                        parsedTemplate.add(fallbackItem)
                    }
                }

                val parsedSuggestions = mutableListOf<PackingItemEntity>()
                val suggestedArr = rootObj.optJSONArray("suggestedItems") ?: JSONArray()
                val suppressPlug = TravelerTemplateCatalog.isUsaOrIndia(forecast.country, forecast.countryCode, forecast.plugTypes)
                for (i in 0 until suggestedArr.length()) {
                    val obj = suggestedArr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    val key = name.lowercase()
                    if (suppressPlug && (key.contains("adapter") || key.contains("plug converter"))) continue
                    if (!seenNames.add(key)) continue
                    val category = PackingCategory.canonicalize(obj.optString("category", "Others"))
                    val qty = obj.optInt("quantity", 1).coerceAtLeast(1)
                    val bag = BagLocation.fromLabel(obj.optString("bagLocation", BagLocation.CARRY_ON.label)).label
                    val reason = ChecklistCalculator.stripSuggestedPrefix(
                        obj.optString("reason", "Suggested by Gemini for ${forecast.city} weather")
                    )
                    parsedSuggestions.add(
                        PackingItemEntity(
                            tripId = tripId,
                            name = name,
                            category = category,
                            quantity = qty,
                            bagLocation = bag,
                            isDoorwayCritical = false,
                            isHighLossReturn = true,
                            weatherReason = "${ChecklistCalculator.SUGGESTED_PREFIX}$reason"
                        )
                    )
                }

                if (parsedTemplate.isNotEmpty()) {
                    val finalSuggestions = if (parsedSuggestions.isNotEmpty()) {
                        parsedSuggestions
                    } else {
                        generateWeatherSuggestionsWithGemini(tripId, days, isWork, carMode, luggageSpace, forecast, parsedTemplate)
                    }
                    return Triple(parsedTemplate, finalSuggestions, false)
                }
            } catch (_: Exception) {
                // Fall through to Stage 2 lightweight Gemini suggestions call below
            }

            // Stage 2 Gemini Fallback: if the combined rule+suggestion call failed/timed out,
            // try the lightweight Gemini suggestions call (2-5 items only) before falling back to rule-based!
            val stage2Suggestions = tryGenerateSuggestionsViaGeminiOnly(
                tripId = tripId,
                days = days,
                isWork = isWork,
                carMode = carMode,
                luggageSpace = luggageSpace,
                forecast = forecast,
                templateItems = fallbackTemplateItems
            )
            if (stage2Suggestions.isNotEmpty()) {
                return Triple(fallbackTemplateItems, stage2Suggestions, false)
            }
        }

        val fallbackSuggestions = ChecklistCalculator.buildDynamicWeatherSuggestions(
            tripId = tripId,
            carMode = carMode,
            forecast = forecast,
            existingItems = fallbackTemplateItems
        )
        return Triple(fallbackTemplateItems, fallbackSuggestions, true)
    }

    private fun tryGenerateSuggestionsViaGeminiOnly(
        tripId: Long,
        days: Int,
        isWork: Boolean,
        carMode: CarTransportMode,
        luggageSpace: LuggageSpace,
        forecast: WeatherForecast,
        templateItems: List<PackingItemEntity>
    ): List<PackingItemEntity> {
        val existingNamesLower = templateItems.map { it.name.trim().lowercase() }.toSet()
        if (geminiApiKey.isBlank()) return emptyList()
        try {
            val dailyWeatherText = if (forecast.dailyBreakdown.isNotEmpty()) {
                forecast.dailyBreakdown.joinToString("\n") { "  - $it" }
            } else {
                "  - Average ${forecast.avgLowF.toInt()}°F low to ${forecast.avgHighF.toInt()}°F high, ${forecast.rainyDaysCount} rainy day(s)"
            }
            val alreadyPackedText = templateItems.joinToString(", ") { "${it.name} (x${it.quantity})" }
            val showPlug = TravelerTemplateCatalog.isPlugInfoNeeded(forecast.country, forecast.countryCode, forecast.plugTypes)

            val prompt = """
                You are PackTrack's dynamic weather & travel advisor.
                The user's Default Template items have ALREADY been added to their packing checklist.
                Now analyze the day-by-day weather forecast, destination, and vehicle/transport mode to suggest 2 to 5 specific, practical additional items or preparations that are NOT already in their template checklist.

                TRIP DETAILS:
                - Destination: ${forecast.city}, ${forecast.country} (International: ${forecast.isInternational}${if (showPlug) ", Plug: ${forecast.plugTypes}" else ""})
                - Duration: $days days
                - Transport / Vehicle: ${carMode.label} (Driving Own Car: ${carMode == CarTransportMode.OWN_CAR}, Rental Car: ${carMode == CarTransportMode.RENTAL_CAR}, Flying: ${carMode != CarTransportMode.OWN_CAR})
                - Luggage Space: ${luggageSpace.label}
                - Purpose: ${if (isWork) "Work / Business" else "Leisure"}

                DAY-BY-DAY WEATHER FORECAST:
                $dailyWeatherText

                ITEMS ALREADY IN CHECKLIST FROM USER'S TEMPLATE (DO NOT DUPLICATE):
                $alreadyPackedText

                INSTRUCTIONS:
                - Suggest 2 to 5 specific items or preparations tailored to this forecast and trip mode (for example: if rain is expected on specific days, suggest a raincoat/compact umbrella and mention which days; if very hot or high UV on a day, suggest sunscreen/sun hat/electrolytes; if snow or freezing weather is expected while driving, suggest snow tires/chains or an ice scraper; if windy, suggest a windbreaker${if (showPlug) "; suggest a ${forecast.plugTypes} adapter" else "; do NOT suggest a power/plug adapter for USA or India trips"}).
                - Make each `reason` conversational, helpful, and specific to the actual forecast days/temperatures.

                Return ONLY a valid JSON array of objects:
                [{"name":"...","category":"On Me|Docs|Electronics|Clothes|Toiletry|Others","quantity":1,"bagLocation":"Personal|Carry-On|Check-In","reason":"..."}]
            """.trimIndent()

            val allowedCategories = PackTrackThemeState.allCategories(templateItems.map { it.category })
            val rawText = callGeminiGenerateContent(prompt, allowedCategories)
            val cleanJson = extractCleanJson(rawText) ?: rawText
            val arr = JSONArray(cleanJson)
            val suggestions = mutableListOf<PackingItemEntity>()
            val seenSuggested = mutableSetOf<String>()
            val suppressPlug = !showPlug
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val key = name.lowercase()
                if (suppressPlug && (key.contains("adapter") || key.contains("plug converter"))) continue
                if (key in existingNamesLower || !seenSuggested.add(key)) continue
                val category = PackingCategory.canonicalize(obj.optString("category", "Others"))
                val qty = obj.optInt("quantity", 1).coerceAtLeast(1)
                val bag = BagLocation.fromLabel(obj.optString("bagLocation", BagLocation.CARRY_ON.label)).label
                val reason = ChecklistCalculator.stripSuggestedPrefix(
                    obj.optString("reason", "Suggested by Gemini for ${forecast.city} weather")
                )
                suggestions.add(
                    PackingItemEntity(
                        tripId = tripId,
                        name = name,
                        category = category,
                        quantity = qty,
                        bagLocation = bag,
                        isDoorwayCritical = false,
                        isHighLossReturn = true,
                        weatherReason = "${ChecklistCalculator.SUGGESTED_PREFIX}$reason"
                    )
                )
            }
            return suggestions
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Passes the trip context, existing template-based items, and full day-by-day Open-Meteo
     * weather forecast to Gemini so it dynamically suggests additional weather- and vehicle-aware
     * items (each tagged with `[SUGGESTED] `) that the user can choose to add or dismiss.
     */
    private fun generateWeatherSuggestionsWithGemini(
        tripId: Long,
        days: Int,
        isWork: Boolean,
        carMode: CarTransportMode,
        luggageSpace: LuggageSpace,
        forecast: WeatherForecast,
        templateItems: List<PackingItemEntity>
    ): List<PackingItemEntity> {
        val existingNamesLower = templateItems.map { it.name.trim().lowercase() }.toSet()

        if (geminiApiKey.isNotBlank()) {
            try {
                val dailyWeatherText = if (forecast.dailyBreakdown.isNotEmpty()) {
                    forecast.dailyBreakdown.joinToString("\n") { "  - $it" }
                } else {
                    "  - Average ${forecast.avgLowF.toInt()}°F low to ${forecast.avgHighF.toInt()}°F high, ${forecast.rainyDaysCount} rainy day(s)"
                }
                val alreadyPackedText = templateItems.joinToString(", ") { "${it.name} (x${it.quantity})" }
                val showPlug = TravelerTemplateCatalog.isPlugInfoNeeded(forecast.country, forecast.countryCode, forecast.plugTypes)

                val prompt = """
                    You are PackTrack's dynamic weather & travel advisor.
                    The user's Default Template items have ALREADY been added to their packing checklist.
                    Now analyze the day-by-day weather forecast, destination, and vehicle/transport mode to suggest 2 to 5 specific, practical additional items or preparations that are NOT already in their template checklist.

                    TRIP DETAILS:
                    - Destination: ${forecast.city}, ${forecast.country} (International: ${forecast.isInternational}${if (showPlug) ", Plug: ${forecast.plugTypes}" else ""})
                    - Duration: $days days
                    - Transport / Vehicle: ${carMode.label} (Driving Own Car: ${carMode == CarTransportMode.OWN_CAR}, Rental Car: ${carMode == CarTransportMode.RENTAL_CAR}, Flying: ${carMode != CarTransportMode.OWN_CAR})
                    - Luggage Space: ${luggageSpace.label}
                    - Purpose: ${if (isWork) "Work / Business" else "Leisure"}

                    DAY-BY-DAY WEATHER FORECAST:
                    $dailyWeatherText

                    ITEMS ALREADY IN CHECKLIST FROM USER'S TEMPLATE (DO NOT DUPLICATE):
                    $alreadyPackedText

                    INSTRUCTIONS:
                    - Suggest 2 to 5 specific items or preparations tailored to this forecast and trip mode (for example: if rain is expected on specific days, suggest a raincoat/compact umbrella and mention which days; if very hot or high UV on a day, suggest sunscreen/sun hat/electrolytes; if snow or freezing weather is expected while driving, suggest snow tires/chains or an ice scraper; if windy, suggest a windbreaker${if (showPlug) "; suggest a ${forecast.plugTypes} adapter" else "; do NOT suggest a power/plug adapter for USA or India trips"}).
                    - Make each `reason` conversational, helpful, and specific to the actual forecast days/temperatures (e.g. "We expect rain on Day 2 (54°F) — carry a raincoat or compact umbrella", "Very hot on Day 1 (92°F, UV 9) — pack sunscreen and a sun hat", "Snow expected on Day 3 (26°F low) while driving — snow tires/chains or an ice scraper might be needed").

                    Return ONLY a valid JSON array of objects:
                    [{"name":"...","category":"On Me|Docs|Electronics|Clothes|Toiletry|Others","quantity":1,"bagLocation":"Personal|Carry-On|Check-In","reason":"..."}]
                """.trimIndent()

                val allowedCategories = PackTrackThemeState.allCategories(templateItems.map { it.category })
                val rawText = callGeminiGenerateContent(prompt, allowedCategories)
                val jsonStart = rawText.indexOf('[')
                val jsonEnd = rawText.lastIndexOf(']')
                if (jsonStart != -1 && jsonEnd > jsonStart) {
                    val arr = JSONArray(rawText.substring(jsonStart, jsonEnd + 1))
                    val suggestions = mutableListOf<PackingItemEntity>()
                    val seenSuggested = mutableSetOf<String>()
                    val suppressPlug = !showPlug
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name", "").trim()
                        if (name.isEmpty()) continue
                        val key = name.lowercase()
                        if (suppressPlug && (key.contains("adapter") || key.contains("plug converter"))) continue
                        if (key in existingNamesLower || !seenSuggested.add(key)) continue
                        val category = PackingCategory.canonicalize(obj.optString("category", "Others"))
                        val qty = obj.optInt("quantity", 1).coerceAtLeast(1)
                        val bag = BagLocation.fromLabel(obj.optString("bagLocation", BagLocation.CARRY_ON.label)).label
                        val reason = ChecklistCalculator.stripSuggestedPrefix(
                            obj.optString("reason", "Suggested by Gemini for ${forecast.city} weather")
                        )
                        suggestions.add(
                            PackingItemEntity(
                                tripId = tripId,
                                name = name,
                                category = category,
                                quantity = qty,
                                bagLocation = bag,
                                isDoorwayCritical = false,
                                isHighLossReturn = true,
                                weatherReason = "${ChecklistCalculator.SUGGESTED_PREFIX}$reason"
                            )
                        )
                    }
                    if (suggestions.isNotEmpty()) {
                        return suggestions
                    }
                }
            } catch (_: Exception) {
                // Fall back to dynamic day-by-day forecast analyzer below
            }
        }

        return ChecklistCalculator.buildDynamicWeatherSuggestions(
            tripId = tripId,
            carMode = carMode,
            forecast = forecast,
            existingItems = templateItems
        )
    }

    private fun buildItemObjectSchema(
        allowedCategories: List<String> = PackingCategory.orderedDisplayNames
    ): JSONObject = JSONObject().apply {
        val uniqueCategories = (PackingCategory.orderedDisplayNames + allowedCategories)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        put("type", "OBJECT")
        put(
            "properties",
            JSONObject().apply {
                put("name", JSONObject().put("type", "STRING"))
                put(
                    "category",
                    JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray(uniqueCategories))
                    }
                )
                put("quantity", JSONObject().put("type", "INTEGER"))
                put(
                    "bagLocation",
                    JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray(listOf("Personal", "Carry-On", "Check-In")))
                    }
                )
                put("reason", JSONObject().put("type", "STRING"))
            }
        )
        put("required", JSONArray(listOf("name", "category", "quantity", "bagLocation", "reason")))
    }

    private fun callGeminiEvaluateAndSuggest(
        prompt: String,
        allowedCategories: List<String> = PackingCategory.orderedDisplayNames
    ): String {
        val responseSchema = JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "templateItems",
                        JSONObject().apply {
                            put("type", "ARRAY")
                            put("items", buildItemObjectSchema(allowedCategories))
                        }
                    )
                    put(
                        "suggestedItems",
                        JSONObject().apply {
                            put("type", "ARRAY")
                            put("items", buildItemObjectSchema(allowedCategories))
                        }
                    )
                }
            )
            put("required", JSONArray(listOf("templateItems", "suggestedItems")))
        }
        return postGeminiStructuredRequest(prompt, responseSchema)
    }

    private fun callGeminiGenerateContent(
        prompt: String,
        allowedCategories: List<String> = PackingCategory.orderedDisplayNames
    ): String {
        val responseSchema = JSONObject().apply {
            put("type", "ARRAY")
            put("description", "2 to 5 context-aware weather and trip packing suggestions not already in the template.")
            put("items", buildItemObjectSchema(allowedCategories))
        }
        return postGeminiStructuredRequest(prompt, responseSchema)
    }

    private fun resolveModelCascade(): List<String> {
        val now = System.currentTimeMillis()
        val existing = cachedDiscoveredModels
        if (!existing.isNullOrEmpty() && now - cachedCascadeAtMs < 3600_000L) {
            return rankModelIds(existing)
        }
        try {
            val conn = (URL("$MODELS_BASE_URL?key=$geminiApiKey").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val arr = root.optJSONArray("models") ?: JSONArray()
                val discovered = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val name = m.optString("name", "").removePrefix("models/").trim()
                    if (name.isEmpty()) continue
                    val desc = m.optString("description", "")
                    val outLimit = m.optInt("outputTokenLimit", 0)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsGen = methods == null
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") {
                                supportsGen = true
                                break
                            }
                        }
                    }
                    if (supportsGen && scoreGeminiModelId(name, desc, outLimit) > 0) {
                        discovered.add(name)
                    }
                }
                if (discovered.isNotEmpty()) {
                    cachedDiscoveredModels = discovered
                    cachedCascadeAtMs = now
                    return rankModelIds(discovered)
                }
            }
        } catch (_: Exception) {
            // Ignore discovery error and fall back to default ranked cascade
        }
        return rankModelIds(emptyList())
    }

    private fun postGeminiStructuredRequest(
        prompt: String,
        responseSchema: JSONObject,
        maxRetriesPerModel: Int = 2
    ): String {
        fun buildPayloadBytes(includeSchema: Boolean): ByteArray {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.2)
                        put("responseMimeType", "application/json")
                        if (includeSchema) {
                            put("responseSchema", responseSchema)
                        }
                    }
                )
            }
            return payload.toString().toByteArray(Charsets.UTF_8)
        }

        val schemaBytes = buildPayloadBytes(includeSchema = true)
        val plainJsonBytes = buildPayloadBytes(includeSchema = false)
        val primaryCascade = resolveModelCascade()
        val fullCascade = primaryCascade + EMERGENCY_LAST_RESORT_MODELS.filterNot { it in primaryCascade }

        for (modelId in fullCascade) {
            var useSchema = true
            for (attempt in 0..maxRetriesPerModel) {
                try {
                    val conn = (URL("$MODELS_BASE_URL/$modelId:generateContent?key=$geminiApiKey").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 10000
                        readTimeout = 25000
                        doOutput = true
                    }
                    conn.outputStream.use { it.write(if (useSchema) schemaBytes else plainJsonBytes) }
                    val status = conn.responseCode
                    if (status !in 200..299) {
                        // If a model rejects nested responseSchema with 400, retry immediately with plain JSON mode
                        if (status == 400 && useSchema) {
                            useSchema = false
                            continue
                        }
                        // 404 (model not found) or 401/403 -> immediately try next model in cascade
                        if (status == 404 || status == 401 || status == 403) {
                            break
                        }
                        // Transient 429 / 5xx / 408 -> backoff and retry on this model before failing over
                        if (attempt < maxRetriesPerModel) {
                            Thread.sleep(350L * (attempt + 1))
                            continue
                        }
                        break
                    }
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val text = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "") ?: ""
                    val cleanJson = extractCleanJson(text)
                    if (cleanJson != null) {
                        lastWorkingModel = modelId
                        return cleanJson
                    }
                    if (attempt < maxRetriesPerModel) {
                        Thread.sleep(350L * (attempt + 1))
                    }
                } catch (_: Exception) {
                    if (attempt < maxRetriesPerModel) {
                        try {
                            Thread.sleep(350L * (attempt + 1))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        throw IllegalStateException("All Gemini models in cascade failed after retries.")
    }

    private suspend fun handleLocalSmartVoiceTurn(userSpeech: String, activeTripId: Long?): String {
        val lower = userSpeech.lowercase()

        // 1. Modify or refresh an existing trip ("weather changed", "refresh weather", "own car", "rental car", "add ...")
        if (activeTripId != null && (
                lower.contains("weather") || lower.contains("refresh") || lower.contains("recheck") ||
                lower.contains("extend") || lower.contains("change to") || lower.contains("add ")
            ) && !lower.contains("going to ") && !lower.contains("trip to ")
        ) {
            if (lower.contains("add ")) {
                val itemName = userSpeech.substringAfter("add ", "").substringAfter("Add ", "").trim()
                if (itemName.isNotEmpty()) {
                    val cat = when {
                        lower.contains("shirt") || lower.contains("coat") || lower.contains("thermal") || lower.contains("jacket") || lower.contains("shoe") -> PackingCategory.CLOTHES
                        lower.contains("doc") || lower.contains("insurance") || lower.contains("passport") || lower.contains("id") -> PackingCategory.DOCS
                        lower.contains("charger") || lower.contains("cable") || lower.contains("adapter") -> PackingCategory.ELECTRONICS
                        else -> PackingCategory.OTHERS
                    }
                    dao.insertSingleItem(
                        PackingItemEntity(
                            tripId = activeTripId,
                            name = itemName.replaceFirstChar { it.uppercase() },
                            category = cat.displayName,
                            quantity = 1,
                            isDoorwayCritical = cat == PackingCategory.ON_ME || cat == PackingCategory.DOCS,
                            isHighLossReturn = true,
                            weatherReason = "Added manually"
                        )
                    )
                    val msg = "Added '${itemName.replaceFirstChar { it.uppercase() }}' (${cat.displayName}) to your current trip!"
                    dao.updateTripConversation(activeTripId, null, msg)
                    return msg
                }
            }
            val newDays = Regex("""(\d+)\s*(?:day|night)""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            return refreshWeatherAndUpdateTrip(activeTripId, newDays)
        }

        // 2. Mode switching by voice ("doorway check", "hotel checkout")
        if (activeTripId != null && (lower.contains("doorway") || lower.contains("leave") || lower.contains("checkout") || lower.contains("return"))) {
            val mode = if (lower.contains("doorway") || lower.contains("leave")) TripMode.DOORWAY else TripMode.RETURN_SWEEP
            dao.updateTripMode(activeTripId, mode.code)
            return if (mode == TripMode.DOORWAY) {
                "Switched to 60-Second Doorway Check: showing only your must-have departure items."
            } else {
                "Switched to Hotel Checkout Sweep: check wall outlets for chargers and the room safe for travel documents!"
            }
        }

        // 3. Checking off items by voice ("mark keys and id packed")
        if (activeTripId != null && (lower.contains("mark ") || lower.contains("pack ") || lower.contains("packed") || lower.contains("check "))) {
            val items = dao.getItemsForTrip(activeTripId)
            var count = 0
            for (item in items) {
                val keyword = item.name.substringBefore(" ").lowercase()
                if (lower.contains(keyword)) {
                    dao.setItemPackedOutbound(item.id, true)
                    count++
                }
            }
            if (count > 0) {
                return "Checked off $count items in your checklist!"
            }
        }

        // 4. Starting a new trip ("Going to Tokyo for 6 days", "Seattle 4 days", "Austin")
        val daysRegex = Regex("""(\d+)\s*(?:day|night|d\b)""")
        val days = daysRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        val cityRegex = Regex("""(?:to|in|for)\s+([a-zA-Z\s]{3,20}?)(?:\s+for|\s+on|\s+next|\s+\d|$)""", RegexOption.IGNORE_CASE)
        val rawCity = cityRegex.find(userSpeech)?.groupValues?.get(1)?.trim()
            ?: TravelerTemplateCatalog.KNOWN_CITIES.firstOrNull { lower.contains(it.lowercase()) }
            ?: userSpeech.trim().takeWhile { it.isLetter() || it == ' ' }.trim().takeIf { it.length in 3..20 }

        if (rawCity != null) {
            val isWork = !lower.contains("leisure")
            val carMode = if (lower.contains("own car") || lower.contains("my car") || lower.contains("driving")) {
                CarTransportMode.OWN_CAR
            } else {
                CarTransportMode.RENTAL_CAR
            }
            return createAndSaveTrip(rawCity, days, isWork, carMode).second
        }

        return "Say where and how long you're traveling (e.g. 'Seattle 4 days'), or say 'Refresh weather' or 'Add umbrella' on your current trip!"
    }

    private suspend fun callGeminiInteractionsApi(userSpeech: String, activeTripId: Long?): String {
        val activeTrip = activeTripId?.let { dao.getTripById(it) }
        val prevId = activeTrip?.lastInteractionId

        val requestJson = JSONObject().apply {
            put("model", MODEL)
            put(
                "system_instruction",
                "You are PackTrack, an on-device Android voice packing assistant. " +
                    "1. When the user mentions a NEW trip destination/duration, call `save_trip_checklist_to_db` immediately so the checklist appears in the app. " +
                    "2. If the user asks to re-check weather, change trip days, or rediscuss an existing trip, call `refresh_or_modify_existing_trip`. " +
                    "3. If the user asks to check off items or switch modes (DOORWAY / RETURN_SWEEP), call `mark_items_packed_in_db`."
            )
            put(
                "input",
                if (activeTrip != null) {
                    "[Active Saved Trip ID=${activeTrip.id}: ${activeTrip.destination}, ${activeTrip.durationDays} days, ${activeTrip.avgLowF.toInt()}F-${activeTrip.avgHighF.toInt()}F] $userSpeech"
                } else {
                    userSpeech
                }
            )
            put("tools", buildToolsJsonArray())
            if (!prevId.isNullOrBlank()) {
                put("previous_interaction_id", prevId)
            }
        }

        var response = postInteraction(requestJson)
        var newInteractionId: String? = response.optString("id").takeIf { it.isNotEmpty() } ?: prevId

        val steps = response.optJSONArray("steps") ?: JSONArray()
        val functionResults = JSONArray()

        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.optString("type") == "function_call") {
                val fnName = step.getString("name")
                val callId = step.getString("id")
                val args = step.optJSONObject("arguments") ?: JSONObject()

                val toolResultJson = executeLocalTool(fnName, args, activeTripId)
                functionResults.put(
                    JSONObject().apply {
                        put("type", "function_result")
                        put("name", fnName)
                        put("call_id", callId)
                        put("result", JSONArray().put(JSONObject().put("type", "text").put("text", toolResultJson.toString())))
                    }
                )
            }
        }

        if (functionResults.length() > 0) {
            val followUp = JSONObject().apply {
                put("model", MODEL)
                put("previous_interaction_id", response.getString("id"))
                put("input", functionResults)
                put("tools", buildToolsJsonArray())
            }
            response = postInteraction(followUp)
            newInteractionId = response.optString("id").takeIf { it.isNotEmpty() } ?: newInteractionId
        }

        val reply = response.optString("output_text", "Your PackTrack checklist has been updated!")
        if (activeTripId != null) {
            dao.updateTripConversation(activeTripId, newInteractionId, reply)
        }
        return reply
    }

    private suspend fun executeLocalTool(name: String, args: JSONObject, activeTripId: Long?): JSONObject {
        return when (name) {
            "save_trip_checklist_to_db" -> {
                val dest = args.optString("destination", "London")
                val days = args.optInt("duration_days", 5)
                val isWork = args.optBoolean("is_work_trip", true)
                val isOwnCar = args.optBoolean("is_own_car", false)
                val carMode = if (isOwnCar) CarTransportMode.OWN_CAR else CarTransportMode.RENTAL_CAR
                val (tripId, summary) = createAndSaveTrip(dest, days, isWork, carMode)
                JSONObject().put("status", "saved").put("trip_id", tripId).put("summary", summary)
            }
            "refresh_or_modify_existing_trip" -> {
                val tId = if (args.has("trip_id")) args.getLong("trip_id") else (activeTripId ?: 1L)
                val newDays = if (args.has("new_duration_days")) args.getInt("new_duration_days") else null
                val summary = refreshWeatherAndUpdateTrip(tId, newDays)
                JSONObject().put("status", "refreshed").put("summary", summary)
            }
            "mark_items_packed_in_db" -> {
                val tId = if (args.has("trip_id")) args.getLong("trip_id") else (activeTripId ?: 1L)
                val mode = args.optString("trip_mode", "")
                if (mode.isNotEmpty()) {
                    dao.updateTripMode(tId, mode)
                }
                JSONObject().put("status", "updated").put("trip_id", tId)
            }
            else -> JSONObject().put("error", "Unknown tool $name")
        }
    }

    private fun buildToolsJsonArray(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "function")
            put("name", "save_trip_checklist_to_db")
            put("description", "Generates and saves a new categorized packing checklist into the local Android Room SQLite database.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("destination", JSONObject().put("type", "string"))
                    put("duration_days", JSONObject().put("type", "integer"))
                    put("is_work_trip", JSONObject().put("type", "boolean"))
                    put("is_own_car", JSONObject().put("type", "boolean"))
                })
                put("required", JSONArray().put("destination").put("duration_days"))
            })
        })
        put(JSONObject().apply {
            put("type", "function")
            put("name", "refresh_or_modify_existing_trip")
            put("description", "Re-checks live weather for an existing saved trip and updates clothes/quantities while preserving already packed checkmarks.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("trip_id", JSONObject().put("type", "integer"))
                    put("new_duration_days", JSONObject().put("type", "integer"))
                })
            })
        })
        put(JSONObject().apply {
            put("type", "function")
            put("name", "mark_items_packed_in_db")
            put("description", "Switches trip mode ('OUTBOUND', 'DOORWAY', 'RETURN_SWEEP') or marks items packed in the local DB.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("trip_id", JSONObject().put("type", "integer"))
                    put("trip_mode", JSONObject().put("type", "string"))
                })
            })
        })
    }

    private fun postInteraction(payload: JSONObject): JSONObject {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", geminiApiKey)
            doOutput = true
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(responseText)
    }
}
