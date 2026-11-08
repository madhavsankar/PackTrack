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
}

