package com.packtrack.app.domain

import com.packtrack.app.data.PackingItemEntity
import com.packtrack.app.data.TemplateRuleEntity
import kotlin.math.ceil

/**
 * Pure deterministic Checklist & Weather Quantity Synthesizer.
 * Evaluates the user's [TemplateRuleEntity] rules across:
 *  - `always`
 *  - `own_car` (personal car)
 *  - `work`
 *  - `rental_car`
 *  - `international`
 *  - `flight`
 *  - `cold` (avgLowF <= 58°F)
 *  - `rain` (rainyDaysCount > 0)
 *  - `sunny` (avgHighF >= 68°F or rainyDaysCount == 0)
 */
object ChecklistCalculator {

    const val SUGGESTED_PREFIX = "[SUGGESTED] "

    fun isSuggested(item: PackingItemEntity): Boolean =
        item.weatherReason.startsWith(SUGGESTED_PREFIX)

    fun stripSuggestedPrefix(reason: String): String =
        reason.removePrefix(SUGGESTED_PREFIX).trim()

    fun buildChecklistForTrip(
        tripId: Long,
        days: Int,
        isWork: Boolean,
        carMode: CarTransportMode,
        luggageSpace: LuggageSpace = LuggageSpace.CARRY_ON,
        forecast: WeatherForecast,
        customTemplateRules: List<TemplateRuleEntity> = TravelerTemplateCatalog.buildDefaultGenericTemplateRules()
    ): List<PackingItemEntity> {
        val list = mutableListOf<PackingItemEntity>()
        val seenNames = mutableSetOf<String>()

        val isFlight = carMode != CarTransportMode.OWN_CAR
        val effectiveLuggage = when {
            carMode == CarTransportMode.OWN_CAR -> LuggageSpace.TWO_CHECKED_BAGS // Personal car: storage space is not an issue
            forecast.isInternational -> LuggageSpace.TWO_CHECKED_BAGS           // International: assume 2 check-in bags
            else -> luggageSpace
        }

        val effectiveDays = when (effectiveLuggage) {
            LuggageSpace.PERSONAL_ONLY -> minOf(days, 3)
            LuggageSpace.CARRY_ON -> minOf(days, 6)
            LuggageSpace.CHECKED_BAG, LuggageSpace.TWO_CHECKED_BAGS -> days
        }

        fun addEntry(
            name: String,
            category: String,
            quantity: Int = 1,
            bagLocation: String = BagLocation.CARRY_ON.label,
            isDoorwayCritical: Boolean = false,
            isHighLossReturn: Boolean = false,
            reason: String = ""
        ) {
            val key = name.trim().lowercase()
            if (key.isEmpty() || !seenNames.add(key)) return
            val canonicalCategory = PackingCategory.canonicalize(category)
            val adjustedBag = if (effectiveLuggage == LuggageSpace.PERSONAL_ONLY && bagLocation == BagLocation.CARRY_ON.label) {
                BagLocation.PERSONAL_ITEM.label
            } else {
                bagLocation
            }
            list.add(
                PackingItemEntity(
                    tripId = tripId,
                    name = name,
                    category = canonicalCategory,
                    quantity = maxOf(1, quantity),
                    bagLocation = adjustedBag,
                    isDoorwayCritical = isDoorwayCritical,
                    isHighLossReturn = isHighLossReturn,
                    weatherReason = reason
                )
            )
        }

        // Evaluate ONLY the user's template rules first (all template-based items come first)
        val nlRuleRegex = Regex("""^if\s+(.+?),\s*(.+?)\s+should\s+be\s+added\.?$""", RegexOption.IGNORE_CASE)
        for (rule in customTemplateRules) {
            val nlMatch = nlRuleRegex.matchEntire(rule.name.trim())
            val rawCondition = if (nlMatch != null && (rule.moduleKey.isBlank() || rule.moduleKey.equals("Always", ignoreCase = true))) {
                nlMatch.groupValues[1]
            } else {
                rule.moduleKey
            }
            val effectiveName = if (nlMatch != null) {
                nlMatch.groupValues[2].trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            } else {
                rule.name
            }

            val shouldInclude = evaluateRuleCondition(
                rawCondition = rawCondition,
                days = days,
                isWork = isWork,
                carMode = carMode,
                isFlight = isFlight,
                forecast = forecast
            )

            if (shouldInclude) {
                val qty = if (rule.scaleByDays) {
                    maxOf(1, ceil(effectiveDays * rule.ratioPerDay).toInt())
                } else {
                    1
                }
                val summary = RuleCondition.formatSummary(rawCondition)
                val note = when {
                    summary != "Always" -> summary
                    rule.scaleByDays -> "Scaled for ${effectiveDays}d"
                    else -> ""
                }
                addEntry(
                    name = effectiveName,
                    category = rule.category,
                    quantity = qty,
                    bagLocation = BagLocation.fromLabel(rule.bagLocation).label,
                    isDoorwayCritical = rule.isDoorwayCritical,
                    isHighLossReturn = rule.isHighLossReturn,
                    reason = note
                )
            }
        }

        return list
    }

    /**
     * Multi-clause compound condition evaluator used as deterministic baseline & offline fallback
     * when Gemini API is unavailable. Evaluates ALL constraints present in [rawCondition] (AND semantics):
     *  - Transport / vehicle clauses (`personal car` / `own car`, `rental`, `flight` / `flying`)
     *  - Purpose clauses (`work` / `office` / `business`)
     *  - International clauses (`international`)
     *  - Weather clauses (`rain` / `wet`, `cold` / `winter`, `sun` / `warm` / `hot`, `snow`)
     *  - Duration clauses (`more than N days`, `at least N days`, `less than N days`)
     *  - Destination city/country clauses (e.g. `go to India`, `in Japan`, `to London`)
     */
    fun evaluateRuleCondition(
        rawCondition: String,
        days: Int,
        isWork: Boolean,
        carMode: CarTransportMode,
        isFlight: Boolean = carMode != CarTransportMode.OWN_CAR,
        forecast: WeatherForecast
    ): Boolean {
        val condLower = rawCondition.trim().lowercase()
        if (condLower.isEmpty() || condLower == "always" || condLower == "default") return true

        // 1. Personal car vs Rental car vs Flight
        if (condLower.contains("personal car") || condLower.contains("own car") || condLower == "own_car" || condLower == "personal_car") {
            if (carMode != CarTransportMode.OWN_CAR) return false
        }
        if (condLower.contains("rental") || condLower == "rental_car") {
            if (carMode != CarTransportMode.RENTAL_CAR) return false
        }
        if (condLower.contains("flight") || condLower.contains("fly") || condLower == "carry_on" || condLower == "checked_bag") {
            if (!isFlight) return false
        }

        // 2. Work / Office / Business
        if (condLower.contains("work") || condLower.contains("office") || condLower.contains("business")) {
            if (!isWork) return false
        }

        // 3. International
        if (condLower.contains("international")) {
            if (!forecast.isInternational) return false
        }

        // 4. Weather clauses (rain, cold, sunny, snow)
        if (Regex("""\b(rain|rainy|wet|drizzle)\b""").containsMatchIn(condLower)) {
            val minRainMatch = Regex("""(?:more than|at\s*least|>=?)\s*(\d+)\s*rain""").find(condLower)
            val minRainDays = minRainMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (forecast.rainyDaysCount < minRainDays) return false
        }
        if (Regex("""\b(cold|winter|freezing|chilly)\b""").containsMatchIn(condLower)) {
            if (forecast.avgLowF > 58.0) return false
        }
        if (Regex("""\b(snow|snowy|blizzard)\b""").containsMatchIn(condLower)) {
            if (!forecast.hasSnow && forecast.minLowF > 32.0) return false
        }
        if (Regex("""\b(sun|sunny|warm|hot)\b""").containsMatchIn(condLower)) {
            if (forecast.avgHighF < 68.0 && forecast.rainyDaysCount > 0) return false
        }

        // 5. Duration clauses ("more than 5 days", "at least 4 days", "less than 3 days")
        Regex("""(?:more than|greater than|over|>)\s*(\d+)\s*day""").find(condLower)?.let { m ->
            val threshold = m.groupValues[1].toIntOrNull() ?: 0
            if (days <= threshold) return false
        }
        Regex("""(?:at\s*least|minimum|>=)\s*(\d+)\s*day""").find(condLower)?.let { m ->
            val threshold = m.groupValues[1].toIntOrNull() ?: 0
            if (days < threshold) return false
        }
        Regex("""(?:less than|under|fewer than|<)\s*(\d+)\s*day""").find(condLower)?.let { m ->
            val threshold = m.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
            if (days >= threshold) return false
        }

        // 6. Destination city / country clauses (e.g., "when we go to India", "in Tokyo", "visiting UK")
        val destPattern = Regex("""\b(?:go(?:ing)?\s+to|trip\s+to|travel(?:ing)?\s+to|visit(?:ing)?|in)\s+([a-z][a-z\s]{1,24}?)(?=\s+(?:and|or|if|when|for|with|$)|[,.]|$)""")
        for (match in destPattern.findAll(condLower)) {
            val candidate = match.groupValues[1].trim()
            val ignoreTokens = setOf(
                "personal car", "own car", "rental car", "a car", "car", "flight", "plane",
                "work", "office", "rain", "cold", "winter", "summer", "the beach", "hotel"
            )
            if (candidate !in ignoreTokens && !candidate.endsWith("days") && !candidate.endsWith("day")) {
                val cityLower = forecast.city.lowercase()
                val countryLower = forecast.country.lowercase()
                val codeLower = forecast.countryCode.lowercase()
                val matchesPlace = cityLower.contains(candidate) ||
                    countryLower.contains(candidate) ||
                    codeLower == candidate
                if (!matchesPlace) return false
            }
        }

        return true
    }

    /**
     * Generates context-aware weather & destination suggestions (each tagged with [SUGGESTED_PREFIX])
     * based on the day-by-day forecast and travel mode when Gemini API is offline or unavailable.
     */
    fun buildDynamicWeatherSuggestions(
        tripId: Long,
        carMode: CarTransportMode,
        forecast: WeatherForecast,
        existingItems: List<PackingItemEntity>
    ): List<PackingItemEntity> {
        val existingLower = existingItems.map { it.name.trim().lowercase() }.toSet()
        val suggestions = mutableListOf<PackingItemEntity>()

        fun addSuggestion(name: String, category: PackingCategory, bag: BagLocation, reason: String) {
            val lower = name.lowercase()
            if (existingLower.any { it == lower || it.contains(lower.substringBefore(" ")) }) return
            suggestions.add(
                PackingItemEntity(
                    tripId = tripId,
                    name = name,
                    category = category.displayName,
                    quantity = 1,
                    bagLocation = bag.label,
                    isDoorwayCritical = false,
                    isHighLossReturn = true,
                    weatherReason = "$SUGGESTED_PREFIX$reason"
                )
            )
        }

        val rainDayLine = forecast.dailyBreakdown.firstOrNull {
            it.contains("Rain", ignoreCase = true) || it.contains("Drizzle", ignoreCase = true) || it.contains("Thunderstorm", ignoreCase = true)
        }?.substringBefore(":")

        if (forecast.rainyDaysCount > 0 && existingLower.none { it.contains("umbrella") || it.contains("rain") || it.contains("poncho") }) {
            val dayRef = if (rainDayLine != null) "starting $rainDayLine (${forecast.rainyDaysCount} rainy day(s) total)" else "across ${forecast.rainyDaysCount} day(s)"
            addSuggestion(
                name = "Raincoat / Compact Umbrella",
                category = PackingCategory.OTHERS,
                bag = BagLocation.CARRY_ON,
                reason = "We expect rain in ${forecast.city} $dayRef — carry a waterproof jacket or compact umbrella."
            )
        }

        if (forecast.hasSnow || forecast.minLowF <= 32.0) {
            val snowDay = forecast.dailyBreakdown.firstOrNull { it.contains("Snow", ignoreCase = true) }?.substringBefore(":") ?: "during your trip"
            if (carMode == CarTransportMode.OWN_CAR || carMode == CarTransportMode.RENTAL_CAR) {
                addSuggestion(
                    name = "Ice Scraper / Snow Tire Check",
                    category = PackingCategory.OTHERS,
                    bag = BagLocation.PERSONAL_ITEM,
                    reason = "Freezing lows (${forecast.minLowF.toInt()}°F) ${if (forecast.hasSnow) "and snow $snowDay " else ""}while driving — snow tires/chains or an ice scraper might be needed."
                )
            }
            if (existingLower.none { it.contains("thermal") || it.contains("glove") || it.contains("beanie") }) {
                addSuggestion(
                    name = "Thermal Base Layer & Gloves",
                    category = PackingCategory.CLOTHES,
                    bag = BagLocation.CARRY_ON,
                    reason = "Temperatures drop to ${forecast.minLowF.toInt()}°F in ${forecast.city} — pack insulated layers, gloves, and a warm hat."
                )
            }
        } else if (forecast.minLowF <= 48.0 && existingLower.none { it.contains("thermal") || it.contains("jacket") || it.contains("coat") || it.contains("sweater") }) {
            addSuggestion(
                name = "Warm Layer / Light Jacket",
                category = PackingCategory.CLOTHES,
                bag = BagLocation.CARRY_ON,
                reason = "Chilly evenings down to ${forecast.minLowF.toInt()}°F in ${forecast.city} — pack a warm layer or fleece."
            )
        }

        if (forecast.maxHighF >= 84.0 || forecast.maxUvIndex >= 7.5) {
            val hotDay = forecast.dailyBreakdown.firstOrNull { it.contains("${forecast.maxHighF.toInt()}°F") }?.substringBefore(":") ?: "during peak afternoon hours"
            if (existingLower.none { it.contains("hat") || it.contains("cap") || it.contains("electrolyte") || it.contains("water bottle") }) {
                addSuggestion(
                    name = "Sun Hat & Reusable Water Bottle",
                    category = PackingCategory.OTHERS,
                    bag = BagLocation.PERSONAL_ITEM,
                    reason = "Highs reach ${forecast.maxHighF.toInt()}°F $hotDay${if (forecast.maxUvIndex >= 7.0) " (UV ${forecast.maxUvIndex.toInt()})" else ""} — stay hydrated and pack sun protection."
                )
            }
        }

        if (forecast.maxWindMph >= 20.0 && existingLower.none { it.contains("windbreaker") || it.contains("shell") }) {
            addSuggestion(
                name = "Windbreaker Shell",
                category = PackingCategory.CLOTHES,
                bag = BagLocation.CARRY_ON,
                reason = "Breezy gusts up to ${forecast.maxWindMph.toInt()} mph forecast in ${forecast.city} — a windproof outer layer will keep you comfortable."
            )
        }

        if (TravelerTemplateCatalog.isPlugInfoNeeded(forecast.country, forecast.countryCode, forecast.plugTypes) && existingLower.none { it.contains("adapter") }) {
            addSuggestion(
                name = "Power Adapter (${forecast.plugTypes})",
                category = PackingCategory.ELECTRONICS,
                bag = BagLocation.PERSONAL_ITEM,
                reason = "${forecast.country} uses ${forecast.plugTypes} outlets — pack a compatible travel adapter."
            )
        }

        if (suggestions.isEmpty() && existingLower.none { it.contains("layer") || it.contains("walking") || it.contains("bottle") }) {
            addSuggestion(
                name = "Collapsible Water Bottle & Light Layer",
                category = PackingCategory.OTHERS,
                bag = BagLocation.PERSONAL_ITEM,
                reason = "Mild ${forecast.avgLowF.toInt()}°F–${forecast.avgHighF.toInt()}°F forecast in ${forecast.city} — handy for day outings and temperature swings."
            )
        }

        return suggestions
    }
}
