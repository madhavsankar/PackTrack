package com.packtrack.app.domain

import com.packtrack.app.data.TemplateRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evaluation & Unit Tests for [ChecklistCalculator] conditional rule evaluation.
 *
 * Verifies that whenever work is selected (`isWork = true`) and a rule specifies that
 * if work is expected to be done ("if work is expected to be done", "if work is expcted to be done",
 * "Only when working", or natural-language "if work is expcted to be done, laptop should be added"),
 * then "Laptop" is guaranteed to be in the final packing list — and excluded when `isWork = false`.
 */
class ChecklistCalculatorEvalTest {

    private val sampleForecast = WeatherForecast(
        city = "Seattle",
        country = "United States",
        countryCode = "US",
        avgHighF = 64.0,
        avgLowF = 49.0,
        rainyDaysCount = 2,
        plugTypes = "Type A/B",
        isInternational = false,
        maxHighF = 67.0,
        minLowF = 46.0,
        hasSnow = false,
        maxWindMph = 12.0,
        maxUvIndex = 4.0,
        dailyBreakdown = listOf(
            "Day 1 (2026-11-07): 48°F–64°F, Rain (70% precip)",
            "Day 2 (2026-11-08): 46°F–67°F, Partly cloudy"
        )
    )

    @Test
    fun eval_whenWorkSelected_andRuleSaysIfWorkExpectedLaptopAdded_laptopIsInFinalList() {
        val condVariants = listOf(
            "if work is expcted to be done",
            "if work is expected to be done",
            "Only when working",
            "work"
        )

        for (condition in condVariants) {
            val rules = listOf(
                TemplateRuleEntity(
                    userId = "eval_user",
                    moduleKey = "Always",
                    name = "Phones",
                    category = "On Me"
                ),
                TemplateRuleEntity(
                    userId = "eval_user",
                    moduleKey = condition,
                    name = "Laptop",
                    category = "Electronics",
                    isDoorwayCritical = true,
                    isHighLossReturn = true,
                    bagLocation = "Personal"
                )
            )

            // 1. Work is selected (isWork = true) -> Laptop MUST be in final list
            val templateItemsWork = ChecklistCalculator.buildChecklistForTrip(
                tripId = 1L,
                days = 4,
                isWork = true,
                carMode = CarTransportMode.RENTAL_CAR,
                luggageSpace = LuggageSpace.CARRY_ON,
                forecast = sampleForecast,
                customTemplateRules = rules
            )
            val suggestedWork = ChecklistCalculator.buildDynamicWeatherSuggestions(
                tripId = 1L,
                carMode = CarTransportMode.RENTAL_CAR,
                forecast = sampleForecast,
                existingItems = templateItemsWork
            )
            val finalListWork = templateItemsWork + suggestedWork
            val finalNamesWork = finalListWork.map { it.name.lowercase() }

            assertTrue(
                "Expected 'Laptop' in final checklist when work is selected for condition '$condition'",
                finalNamesWork.contains("laptop")
            )
            val laptopItem = finalListWork.first { it.name.equals("Laptop", ignoreCase = true) }
            assertFalse(
                "Expected 'Laptop' from template rule to be an active item, not a [SUGGESTED] item",
                ChecklistCalculator.isSuggested(laptopItem)
            )

            // 2. Leisure is selected (isWork = false) -> Laptop MUST NOT be in final list
            val templateItemsLeisure = ChecklistCalculator.buildChecklistForTrip(
                tripId = 2L,
                days = 4,
                isWork = false,
                carMode = CarTransportMode.RENTAL_CAR,
                luggageSpace = LuggageSpace.CARRY_ON,
                forecast = sampleForecast,
                customTemplateRules = rules
            )
            val finalNamesLeisure = templateItemsLeisure.map { it.name.lowercase() }
            assertFalse(
                "Expected 'Laptop' to be excluded when work is NOT selected for condition '$condition'",
                finalNamesLeisure.contains("laptop")
            )
        }
    }

    @Test
    fun eval_whenNaturalLanguageRuleStringSaysIfWorkExpectedLaptopShouldBeAdded_laptopIsInFinalList() {
        val rules = listOf(
            TemplateRuleEntity(
                userId = "eval_user",
                moduleKey = "Always",
                name = "if work is expcted to be done, laptop should be added",
                category = "Electronics"
            )
        )

        val workList = ChecklistCalculator.buildChecklistForTrip(
            tripId = 10L,
            days = 3,
            isWork = true,
            carMode = CarTransportMode.RENTAL_CAR,
            luggageSpace = LuggageSpace.CARRY_ON,
            forecast = sampleForecast,
            customTemplateRules = rules
        )
        assertEquals(listOf("Laptop"), workList.map { it.name })

        val leisureList = ChecklistCalculator.buildChecklistForTrip(
            tripId = 11L,
            days = 3,
            isWork = false,
            carMode = CarTransportMode.RENTAL_CAR,
            luggageSpace = LuggageSpace.CARRY_ON,
            forecast = sampleForecast,
            customTemplateRules = rules
        )
        assertTrue(leisureList.isEmpty())
    }

    @Test
    fun eval_complexMultiClauseCondition_rainAndMoreThan5DaysAndIndia() {
        val complexRule = TemplateRuleEntity(
            userId = "eval_user",
            moduleKey = "Only add this when there is atleast one rainy and and if trip is for more than 5 days and n=only when we go to India",
            name = "Monsoon Trekking Poncho",
            category = "Clothes"
        )

        val indiaRainyForecast = sampleForecast.copy(
            city = "Mumbai",
            country = "India",
            countryCode = "IN",
            rainyDaysCount = 2,
            isInternational = true
        )

        // 1. All 3 clauses satisfied (rain >= 1, days = 7 > 5, destination = India) -> INCLUDED
        val listMatchingAll = ChecklistCalculator.buildChecklistForTrip(
            tripId = 20L,
            days = 7,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            forecast = indiaRainyForecast,
            customTemplateRules = listOf(complexRule)
        )
        assertEquals(listOf("Monsoon Trekking Poncho"), listMatchingAll.map { it.name })

        // 2. Trip is only 4 days (fails "more than 5 days") -> EXCLUDED
        val listShortTrip = ChecklistCalculator.buildChecklistForTrip(
            tripId = 21L,
            days = 4,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            forecast = indiaRainyForecast,
            customTemplateRules = listOf(complexRule)
        )
        assertTrue(listShortTrip.isEmpty())

        // 3. No rain (fails "atleast one rainy") -> EXCLUDED
        val listDryIndia = ChecklistCalculator.buildChecklistForTrip(
            tripId = 22L,
            days = 7,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            forecast = indiaRainyForecast.copy(rainyDaysCount = 0),
            customTemplateRules = listOf(complexRule)
        )
        assertTrue(listDryIndia.isEmpty())

        // 4. Destination is Seattle, US (fails "only when we go to India") -> EXCLUDED
        val listSeattleRainy7Days = ChecklistCalculator.buildChecklistForTrip(
            tripId = 23L,
            days = 7,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            forecast = sampleForecast,
            customTemplateRules = listOf(complexRule)
        )
        assertTrue(listSeattleRainy7Days.isEmpty())
    }

    @Test
    fun eval_generalizedModelCascade_ranksFutureVersionsLike4_3And3_2AndFiltersNonText() {
        val discovered = listOf(
            "models/text-embedding-004",
            "models/gemma-4-31b-it",
            "models/gemini-3.1-flash-image",
            "models/gemini-3-pro-image",
            "models/gemini-omni-1.1-flash",
            "models/gemini-2.5-flash",
            "models/gemini-2.0-flash",
            "models/gemini-4-argon",
            "models/gemini-3.2-flash-lite",
            "models/gemini-4.3-flash",
            "models/gemini-3.8-flash-tts"
        )
        val ranked = com.packtrack.app.agent.GeminiVoiceAssistant.rankModelIds(
            discoveredIds = discovered,
            preferredWorkingModel = null
        )
        // 1. Primary Flash tier first (4.3-flash > 3.8-flash > 3.6-flash > flash-latest)
        assertEquals("gemini-4.3-flash", ranked[0])
        // 2. Flash-Lite tier next (3.2-flash-lite falls right behind 3.5-flash-lite and ahead of 3.1-flash-lite)
        assertTrue(ranked.indexOf("gemini-4.3-flash") < ranked.indexOf("gemini-3.2-flash-lite"))
        // 3. Heavy reasoning Pro/Argon tier acts as emergency fallback after fast Flash/Flash-Lite tiers
        assertTrue(ranked.indexOf("gemini-3.2-flash-lite") < ranked.indexOf("gemini-4-argon"))
        // 4. Non-text modalities, open Gemma models, and legacy < 3.0 models are strictly excluded
        assertFalse(ranked.contains("text-embedding-004"))
        assertFalse(ranked.contains("gemma-4-31b-it"))
        assertFalse(ranked.contains("gemini-3.1-flash-image"))
        assertFalse(ranked.contains("gemini-3-pro-image"))
        assertFalse(ranked.contains("gemini-omni-1.1-flash"))
        assertFalse(ranked.contains("gemini-3.8-flash-tts"))
        assertFalse(ranked.contains("gemini-2.5-flash"))
        assertFalse(ranked.contains("gemini-2.0-flash"))
    }

    @Test
    fun eval_luggageSpaceCapsAndQuantityScaling() {
        val scalingRules = listOf(
            TemplateRuleEntity(
                userId = "eval_user",
                moduleKey = "Always",
                name = "Socks",
                category = "Clothes",
                scaleByDays = true,
                ratioPerDay = 1.0,
                bagLocation = "Carry-On"
            ),
            TemplateRuleEntity(
                userId = "eval_user",
                moduleKey = "Always",
                name = "Gym Shorts",
                category = "Gym",
                scaleByDays = true,
                ratioPerDay = 0.5,
                bagLocation = "Carry-On"
            )
        )

        // 1. Personal-only bag caps effectiveDays at 3 and reassigns Carry-On items to Personal
        val personalOnlyItems = ChecklistCalculator.buildChecklistForTrip(
            tripId = 30L,
            days = 10,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            luggageSpace = LuggageSpace.PERSONAL_ONLY,
            forecast = sampleForecast,
            customTemplateRules = scalingRules
        )
        val personalSocks = personalOnlyItems.first { it.name == "Socks" }
        val personalShorts = personalOnlyItems.first { it.name == "Gym Shorts" }
        assertEquals(3, personalSocks.quantity)
        assertEquals(2, personalShorts.quantity) // ceil(3 * 0.5) = 2
        assertEquals("Personal", personalSocks.bagLocation)
        assertEquals("Gym", personalShorts.category) // Custom category preserved

        // 2. Carry-On caps effectiveDays at 6 for domestic flights
        val carryOnItems = ChecklistCalculator.buildChecklistForTrip(
            tripId = 31L,
            days = 10,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            luggageSpace = LuggageSpace.CARRY_ON,
            forecast = sampleForecast,
            customTemplateRules = scalingRules
        )
        assertEquals(6, carryOnItems.first { it.name == "Socks" }.quantity)
        assertEquals("Carry-On", carryOnItems.first { it.name == "Socks" }.bagLocation)

        // 3. Personal Car (OWN_CAR) bypasses carry-on cap and scales for full 10 days
        val ownCarItems = ChecklistCalculator.buildChecklistForTrip(
            tripId = 32L,
            days = 10,
            isWork = false,
            carMode = CarTransportMode.OWN_CAR,
            luggageSpace = LuggageSpace.PERSONAL_ONLY,
            forecast = sampleForecast,
            customTemplateRules = scalingRules
        )
        assertEquals(10, ownCarItems.first { it.name == "Socks" }.quantity)
        assertEquals(5, ownCarItems.first { it.name == "Gym Shorts" }.quantity)

        // 4. International trips automatically upgrade to TWO_CHECKED_BAGS (full 10 days)
        val intlItems = ChecklistCalculator.buildChecklistForTrip(
            tripId = 33L,
            days = 10,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            luggageSpace = LuggageSpace.CARRY_ON,
            forecast = sampleForecast.copy(isInternational = true, country = "Japan", countryCode = "JP"),
            customTemplateRules = scalingRules
        )
        assertEquals(10, intlItems.first { it.name == "Socks" }.quantity)
    }

    @Test
    fun eval_dynamicWeatherSuggestionsAndDeduplication() {
        // 1. Freezing + snow + rental car + high wind + international UK plug
        val extremeForecast = sampleForecast.copy(
            city = "Edinburgh",
            country = "United Kingdom",
            countryCode = "GB",
            avgHighF = 36.0,
            avgLowF = 26.0,
            minLowF = 24.0,
            maxHighF = 38.0,
            rainyDaysCount = 3,
            hasSnow = true,
            maxWindMph = 28.0,
            plugTypes = "Type G (UK 3-pin)",
            isInternational = true,
            dailyBreakdown = listOf("Day 1: 24°F–36°F, Snow & Rain")
        )
        val suggestions = ChecklistCalculator.buildDynamicWeatherSuggestions(
            tripId = 40L,
            carMode = CarTransportMode.RENTAL_CAR,
            forecast = extremeForecast,
            existingItems = emptyList()
        )
        val names = suggestions.map { it.name }
        assertTrue(names.contains("Raincoat / Compact Umbrella"))
        assertTrue(names.contains("Ice Scraper / Snow Tire Check"))
        assertTrue(names.contains("Thermal Base Layer & Gloves"))
        assertTrue(names.contains("Windbreaker Shell"))
        assertTrue(names.contains("Power Adapter (Type G (UK 3-pin))"))
        assertTrue(suggestions.all { ChecklistCalculator.isSuggested(it) })

        // 2. Deduplication: when user already has an Umbrella and Power Adapter in their template, they are not suggested again
        val existing = ChecklistCalculator.buildChecklistForTrip(
            tripId = 41L,
            days = 4,
            isWork = false,
            carMode = CarTransportMode.NO_CAR,
            forecast = extremeForecast,
            customTemplateRules = listOf(
                TemplateRuleEntity(userId = "u", moduleKey = "Always", name = "Compact Umbrella", category = "Others"),
                TemplateRuleEntity(userId = "u", moduleKey = "Always", name = "Universal Power Adapter", category = "Electronics")
            )
        )
        val dedupedSuggestions = ChecklistCalculator.buildDynamicWeatherSuggestions(
            tripId = 41L,
            carMode = CarTransportMode.NO_CAR,
            forecast = extremeForecast,
            existingItems = existing
        )
        val dedupedNames = dedupedSuggestions.map { it.name }
        assertFalse(dedupedNames.contains("Raincoat / Compact Umbrella"))
        assertFalse(dedupedNames.contains("Power Adapter (Type G (UK 3-pin))"))
        assertFalse(dedupedNames.contains("Ice Scraper / Snow Tire Check")) // NO_CAR -> no ice scraper
    }

    @Test
    fun eval_categoryCanonicalizationAndTripDateUtils() {
        // 1. Standard & legacy category normalization + custom category preservation
        assertEquals("On Me", PackingCategory.canonicalize("Leave-the-House Essentials"))
        assertEquals("Docs", PackingCategory.canonicalize("documents"))
        assertEquals("Electronics", PackingCategory.canonicalize("Tech & Chargers"))
        assertEquals("Toiletry", PackingCategory.canonicalize("Toiletries & Health"))
        assertEquals("Others", PackingCategory.canonicalize("Packing & Travel Gear"))
        assertEquals("Camera Gear", PackingCategory.canonicalize("Camera Gear"))
        assertEquals("Baby Supplies", PackingCategory.canonicalize("  Baby Supplies  "))

        // 2. Relative trip date formatting & closest-to-farthest sort order
        val today = java.time.LocalDate.of(2026, 11, 8)
        assertEquals("Today", TripDateUtils.formatRelativeStart("2026-11-08", today))
        assertEquals("Tomorrow", TripDateUtils.formatRelativeStart("2026-11-09", today))
        assertEquals("In 5 days", TripDateUtils.formatRelativeStart("2026-11-13", today))
        assertEquals("Yesterday", TripDateUtils.formatRelativeStart("2026-11-07", today))
        assertEquals("4 days ago", TripDateUtils.formatRelativeStart("2026-11-04", today))

        // Upcoming trips sort before past trips, and closer upcoming trips sort before farther ones
        val keyToday = TripDateUtils.sortKeyClosestToFarthest("2026-11-08", today)
        val keyIn3Days = TripDateUtils.sortKeyClosestToFarthest("2026-11-11", today)
        val keyYesterday = TripDateUtils.sortKeyClosestToFarthest("2026-11-07", today)
        assertTrue(keyToday < keyIn3Days)
        assertTrue(keyIn3Days < keyYesterday)

        // 3. Date range formatting (same month, cross-month, cross-year)
        assertEquals("Nov 10–14", TripDateUtils.formatDateRange("2026-11-10", 4))
        assertEquals("Nov 28 – Dec 3", TripDateUtils.formatDateRange("2026-11-28", 5))
        assertEquals("Dec 29, 2026 – Jan 3, 2027", TripDateUtils.formatDateRange("2026-12-29", 5, "2027-01-03"))
    }

    @Test
    fun eval_androidManifestAndBackNavigationContracts() {
        // Locate android/app/src/main from current working directory (supports running from repo root or android/ or android/app)
        val candidates = listOf(
            java.io.File("src/main"),
            java.io.File("app/src/main"),
            java.io.File("android/app/src/main")
        )
        val mainDir = candidates.firstOrNull { java.io.File(it, "AndroidManifest.xml").exists() }
            ?: throw IllegalStateException("Could not locate AndroidManifest.xml from ${java.io.File(".").absolutePath}")

        val manifestText = java.io.File(mainDir, "AndroidManifest.xml").readText()
        assertTrue("Manifest must set windowSoftInputMode=adjustResize", manifestText.contains("android:windowSoftInputMode=\"adjustResize\""))
        assertTrue("Manifest must declare configChanges for orientation and screenSize", manifestText.contains("android:configChanges=\"orientation|screenSize|screenLayout|keyboardHidden\""))

        val uiDir = java.io.File(mainDir, "java/com/packtrack/app/ui")
        val checklistScreenText = java.io.File(uiDir, "ChecklistScreen.kt").readText()
        assertTrue("ChecklistScreen must use rememberSaveable for state persistence", checklistScreenText.contains("rememberSaveable"))
        assertTrue("ChecklistScreen must maintain a navigation backStack", checklistScreenText.contains("backStack"))
        assertTrue("ChecklistScreen must register a BackHandler for non-HOME screens", checklistScreenText.contains("BackHandler(enabled = currentScreen != AppScreen.HOME)"))

        val homeScreenText = java.io.File(uiDir, "HomeScreen.kt").readText()
        assertTrue("HomeScreen must close open navigation drawer on Back press", homeScreenText.contains("BackHandler(enabled = drawerState.isOpen)"))
        assertTrue("HomeScreen must use FlowRow so badges wrap on narrow screens", homeScreenText.contains("FlowRow("))
        assertTrue("HomeScreen must display formatted date range alongside duration days", homeScreenText.contains("formatDateRange("))

        val interviewScreenText = java.io.File(uiDir, "GeminiInterviewScreen.kt").readText()
        assertTrue("GeminiInterviewScreen must step back through interview stages on Back press", interviewScreenText.contains("fun handleStepBack()"))
        assertTrue("GeminiInterviewScreen must register BackHandler calling handleStepBack", interviewScreenText.contains("BackHandler(enabled = !isBusy)"))
        assertTrue("GeminiInterviewScreen top bar must display formatted date range alongside duration days", interviewScreenText.contains("formatDateRange("))

        val tripChecklistText = java.io.File(uiDir, "TripChecklistScreen.kt").readText()
        assertTrue("TripChecklistScreen must use FlowRow for responsive header badges", tripChecklistText.contains("FlowRow("))
        assertTrue("TripChecklistScreen top bar must display formatted date range alongside duration days", tripChecklistText.contains("formatDateRange("))
    }
}

