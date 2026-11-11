package com.packtrack.app.domain

/**
 * Strongly-typed domain models and static constants for PackTrack.
 */

enum class AppScreen {
    HOME,
    GEMINI_INTERVIEW,
    TRIP_CHECKLIST,
    TEMPLATE_EDITOR
}

enum class PackingCategory(val displayName: String, val sortOrder: Int) {
    ON_ME("On Me", 1),
    DOCS("Docs", 2),
    ELECTRONICS("Electronics", 3),
    CLOTHES("Clothes", 4),
    TOILETRY("Toiletry", 5),
    OTHERS("Others", 6);

    companion object {
        val orderedDisplayNames: List<String> = entries.sortedBy { it.sortOrder }.map { it.displayName }

        fun fromDisplayName(name: String): PackingCategory {
            val clean = name.trim().lowercase()
            return when {
                clean.contains("on me") || clean.contains("basic") -> ON_ME
                clean.contains("doc") -> DOCS
                clean.contains("electronic") || clean.contains("tech") || clean.contains("office") || clean.contains("work") -> ELECTRONICS
                clean.contains("cloth") -> CLOTHES
                clean.contains("toilet") || clean.contains("health") -> TOILETRY
                clean.contains("other") || clean.contains("gear") || clean.contains("packing") -> OTHERS
                else -> entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: OTHERS
            }
        }

        /**
         * Normalizes legacy category labels to the 6 standard categories while preserving
         * any user-created custom categories (e.g. "Gym", "Baby", "Camera Gear").
         */
        fun canonicalize(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return OTHERS.displayName
            val lower = trimmed.lowercase()
            return when {
                lower == "on me" || lower.contains("leave-the-house") || lower == "basics" -> ON_ME.displayName
                lower == "docs" || lower == "documents" -> DOCS.displayName
                lower == "electronics" || lower == "tech & chargers" || lower == "office / work" -> ELECTRONICS.displayName
                lower == "clothes" -> CLOTHES.displayName
                lower == "toiletry" || lower == "toiletries & health" -> TOILETRY.displayName
                lower == "others" || lower == "packing & travel gear" -> OTHERS.displayName
                else -> entries.firstOrNull { it.displayName.equals(trimmed, ignoreCase = true) }?.displayName ?: trimmed
            }
        }
    }
}

enum class TripMode(val code: String, val tabLabel: String, val progressTitle: String) {
    OUTBOUND("OUTBOUND", "Pack", "Outbound Suitcase Progress"),
    DOORWAY("DOORWAY", "Doorway", "60-Sec Doorway Check"),
    RETURN_SWEEP("RETURN_SWEEP", "Checkout", "Hotel Checkout Sweep");

    companion object {
        fun fromCode(code: String): TripMode {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: OUTBOUND
        }
    }
}

enum class CarTransportMode(val tag: String, val label: String) {
    RENTAL_CAR("rental_car", "Flight + Rental Car"),
    NO_CAR("no_car", "Flight (No Rental Car)"),
    OWN_CAR("own_car", "Personal Car (Driving Own Vehicle)")
}

enum class LuggageSpace(val tag: String, val label: String, val description: String) {
    CARRY_ON("carry_on", "Carry-On + Shoulder Bag", "Standard US carry-on suitcase + personal shoulder bag"),
    CHECKED_BAG("checked_bag", "1 Check-In Bag + Carry-On", "1 checked suitcase + standard carry-on & shoulder bag"),
    TWO_CHECKED_BAGS("two_checked_bags", "2 Check-In Bags + Carry-On", "2 checked suitcases + carry-on & shoulder bag"),
    PERSONAL_ONLY("personal_only", "Shoulder Bag Only", "Compact under-seat personal bag only")
}

enum class BagLocation(val label: String) {
    PERSONAL_ITEM("Personal"),
    CARRY_ON("Carry-On"),
    CHECKED("Check-In"),
    ON_ME("Personal"),
    WORN("Personal");

    companion object {
        val selectableEntries: List<BagLocation> = listOf(PERSONAL_ITEM, CARRY_ON, CHECKED)

        fun fromLabel(label: String): BagLocation {
            val clean = label.trim().lowercase()
            return when {
                clean.contains("check") -> CHECKED
                clean.contains("carry") -> CARRY_ON
                else -> PERSONAL_ITEM
            }
        }
    }
}

enum class CarRuleOption(val key: String, val label: String) {
    NO("no", "No"),
    RENTAL("rental_car", "Rental"),
    OWN("own_car", "Own");

    companion object {
        fun fromModuleKey(moduleKey: String): CarRuleOption {
            val tokens = moduleKey.lowercase().split(",").map { it.trim() }
            return when {
                tokens.contains("own_car") || tokens.contains("personal_car") -> OWN
                tokens.contains("rental_car") || tokens.contains("rental_car_leisure") -> RENTAL
                else -> NO
            }
        }
    }
}

enum class RuleCondition(val key: String, val label: String, val sortOrder: Int) {
    ALWAYS("Always", "Always", 1),
    PERSONAL_CAR("Only when taking personal car", "Only when taking personal car", 2),
    WORK("Only when working", "Only when working", 3),
    RENTAL_CAR("Only when taking rental car", "Only when taking rental car", 4),
    INTERNATIONAL("Only for international trips", "Only for international trips", 5),
    FLIGHT("Only when flying", "Only when flying", 6),
    COLD("If expected to be cold", "If expected to be cold", 7),
    RAIN("If rain expected", "If rain expected", 8),
    SUNNY("If sunny", "If sunny", 9);

    companion object {
        fun formatSummary(rawCondition: String): String {
            val trimmed = rawCondition.trim()
            if (trimmed.isEmpty()) return "Always"
            return when (trimmed.lowercase()) {
                "always", "default" -> "Always"
                "own_car", "personal_car" -> "Only when taking personal car"
                "work" -> "Only when working"
                "rental_car", "rental_car_leisure" -> "Only when taking rental car"
                "international" -> "Only for international trips"
                "flight", "packing_gear", "carry_on", "checked_bag" -> "Only when flying"
                "cold" -> "If expected to be cold"
                "rain" -> "If rain expected"
                "sunny" -> "If sunny"
                else -> trimmed
            }
        }
    }
}

data class WeatherForecast(
    val city: String,
    val country: String,
    val countryCode: String,
    val avgHighF: Double,
    val avgLowF: Double,
    val rainyDaysCount: Int,
    val plugTypes: String,
    val isInternational: Boolean,
    val dailyBreakdown: List<String> = emptyList(),
    val hasSnow: Boolean = false,
    val maxHighF: Double = avgHighF,
    val minLowF: Double = avgLowF,
    val maxWindMph: Double = 0.0,
    val maxUvIndex: Double = 0.0
)

data class TemplateItemSpec(
    val name: String,
    val category: PackingCategory,
    val bagLocation: BagLocation = BagLocation.CARRY_ON,
    val isDoorwayCritical: Boolean = false,
    val isHighLossReturn: Boolean = false,
    val defaultNote: String = ""
)

object TripDateUtils {
    fun parseStartDate(raw: String?): java.time.LocalDate? {
        val clean = (raw ?: "").trim()
        if (clean.length < 8) return null
        return try {
            java.time.LocalDate.parse(clean.take(10))
        } catch (_: Exception) {
            null
        }
    }

    fun formatDateRange(
        rawStartDate: String?,
        durationDays: Int,
        rawEndDate: String? = null
    ): String {
        val start = parseStartDate(rawStartDate) ?: java.time.LocalDate.now()
        val end = parseStartDate(rawEndDate) ?: start.plusDays(maxOf(1, durationDays).toLong())
        val fmtMonthDay = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)
        val fmtDay = java.time.format.DateTimeFormatter.ofPattern("d", java.util.Locale.US)
        val fmtFull = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
        return when {
            start.year != end.year -> "${start.format(fmtFull)} – ${end.format(fmtFull)}"
            start.month == end.month -> "${start.format(fmtMonthDay)}–${end.format(fmtDay)}"
            else -> "${start.format(fmtMonthDay)} – ${end.format(fmtMonthDay)}"
        }
    }

    fun daysUntilStart(
        rawStartDate: String?,
        today: java.time.LocalDate = java.time.LocalDate.now()
    ): Long? {
        val date = parseStartDate(rawStartDate) ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(today, date)
    }

    fun formatRelativeStart(
        rawStartDate: String?,
        today: java.time.LocalDate = java.time.LocalDate.now()
    ): String {
        val diff = daysUntilStart(rawStartDate, today) ?: return "Today"
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff in 2L..29L -> "In $diff days"
            diff in 30L..44L -> "In 1 month"
            diff in 45L..364L -> {
                val months = kotlin.math.round(diff / 30.0).toInt().coerceAtLeast(1)
                if (months == 1) "In 1 month" else "In $months months"
            }
            diff >= 365L -> {
                val years = kotlin.math.round(diff / 365.0).toInt().coerceAtLeast(1)
                if (years == 1) "In 1 year" else "In $years years"
            }
            diff == -1L -> "Yesterday"
            diff > -30L -> "${-diff} days ago"
            else -> {
                val months = kotlin.math.round(-diff / 30.0).toInt().coerceAtLeast(1)
                if (months == 1) "1 month ago" else "$months months ago"
            }
        }
    }

    fun sortKeyClosestToFarthest(
        rawStartDate: String?,
        today: java.time.LocalDate = java.time.LocalDate.now()
    ): Long {
        val diff = daysUntilStart(rawStartDate, today) ?: 0L
        return if (diff >= 0L) diff else 1_000_000L + (-diff)
    }

    fun computeEndDate(
        rawStartDate: String?,
        durationDays: Int,
        fallbackToday: java.time.LocalDate = java.time.LocalDate.now()
    ): java.time.LocalDate {
        val start = parseStartDate(rawStartDate) ?: fallbackToday
        return start.plusDays(maxOf(1, durationDays).toLong())
    }

    fun computeDurationDays(
        rawStartDate: String?,
        rawEndDate: String?,
        fallbackDays: Int = 3
    ): Int {
        val start = parseStartDate(rawStartDate)
        val end = parseStartDate(rawEndDate)
        if (start != null && end != null) {
            val diff = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()
            return diff.coerceIn(1, 30)
        }
        return fallbackDays.coerceIn(1, 30)
    }

    data class ParsedTripTags(
        val isWork: Boolean,
        val carMode: CarTransportMode,
        val luggageSpace: LuggageSpace
    )

    fun parseTripTags(tagsCsv: String?): ParsedTripTags {
        val tags = (tagsCsv ?: "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val isWork = if (tags.contains("leisure") && !tags.contains("work")) false else true
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
        return ParsedTripTags(
            isWork = isWork,
            carMode = carMode,
            luggageSpace = luggageSpace
        )
    }

    fun buildTagsCsv(
        isWork: Boolean,
        carMode: CarTransportMode,
        luggageSpace: LuggageSpace,
        isInternational: Boolean = false
    ): String {
        val effectiveLuggage = if (carMode == CarTransportMode.OWN_CAR) {
            LuggageSpace.TWO_CHECKED_BAGS
        } else {
            luggageSpace
        }
        return listOfNotNull(
            if (isWork) "work" else "leisure",
            carMode.tag,
            effectiveLuggage.tag,
            if (isInternational) "international" else null
        ).joinToString(",")
    }
}
