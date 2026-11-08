package com.packtrack.app.domain

import com.packtrack.app.BuildConfig
import com.packtrack.app.data.TemplateRuleEntity

/**
 * Generic Starter Template Catalog & Destination Metadata.
 *
 * Provides a clean, generic baseline template for anonymous profiles.
 * Signed-in users sync their personal template from Cloud Firestore (`packtrack_state/user_state_{userId}`).
 */
object TravelerTemplateCatalog {

    val DEFAULT_USER_ID: String
        get() = BuildConfig.DEFAULT_USER_ID.trim().ifEmpty { "default" }

    val KNOWN_CITIES: List<String> = listOf(
        "London", "Tokyo", "Seattle", "Austin", "New York", "NYC", "Paris", "Zurich",
        "Chicago", "San Francisco", "Miami", "Las Vegas", "Bengaluru", "Mumbai",
        "Singapore", "Sydney", "Boston", "Denver", "Los Angeles", "Dallas", "Atlanta"
    )

    val ALWAYS_PACK_BASICS: List<TemplateItemSpec> = listOf(
        TemplateItemSpec(
            name = "House Keys",
            category = PackingCategory.ON_ME,
            bagLocation = BagLocation.ON_ME,
            isDoorwayCritical = true,
            isHighLossReturn = true
        ),
        TemplateItemSpec(
            name = "Payment Card / Wallet",
            category = PackingCategory.ON_ME,
            bagLocation = BagLocation.ON_ME,
            isDoorwayCritical = true,
            isHighLossReturn = true
        ),
        TemplateItemSpec(
            name = "Sunglasses",
            category = PackingCategory.CLOTHES,
            bagLocation = BagLocation.PERSONAL_ITEM,
            isHighLossReturn = true
        )
    )

    val DEFAULT_DOCUMENTS: List<TemplateItemSpec> = listOf(
        TemplateItemSpec(
            name = "Driver's License / Photo ID",
            category = PackingCategory.ON_ME,
            bagLocation = BagLocation.ON_ME,
            isDoorwayCritical = true,
            isHighLossReturn = true
        ),
        TemplateItemSpec(
            name = "Booking & Hotel Docs",
            category = PackingCategory.DOCS,
            bagLocation = BagLocation.PERSONAL_ITEM,
            isDoorwayCritical = true
        )
    )

    val DEFAULT_TECH: List<TemplateItemSpec> = listOf(
        TemplateItemSpec(
            name = "Phone Charger",
            category = PackingCategory.ELECTRONICS,
            bagLocation = BagLocation.PERSONAL_ITEM,
            isDoorwayCritical = true,
            isHighLossReturn = true
        ),
        TemplateItemSpec(
            name = "Earbuds / Headphones",
            category = PackingCategory.ELECTRONICS,
            bagLocation = BagLocation.PERSONAL_ITEM,
            isHighLossReturn = true
        )
    )

    val DEFAULT_TOILETRIES: List<TemplateItemSpec> = listOf(
        TemplateItemSpec(name = "Sunscreen & Moisturizer", category = PackingCategory.TOILETRY),
        TemplateItemSpec(name = "Lip Balm", category = PackingCategory.TOILETRY)
    )

    val DEFAULT_PACKING_GEAR: List<TemplateItemSpec> = listOf(
        TemplateItemSpec(
            name = "Packing Organizer Bags",
            category = PackingCategory.OTHERS,
            isHighLossReturn = true
        )
    )

    val WORK_MODULE_ITEMS: List<TemplateItemSpec> = listOf(
        TemplateItemSpec(
            name = "Laptop",
            category = PackingCategory.ELECTRONICS,
            bagLocation = BagLocation.PERSONAL_ITEM,
            isDoorwayCritical = true,
            isHighLossReturn = true
        ),
        TemplateItemSpec(
            name = "Laptop Charger",
            category = PackingCategory.ELECTRONICS,
            bagLocation = BagLocation.PERSONAL_ITEM,
            isDoorwayCritical = true,
            isHighLossReturn = true
        )
    )

    fun buildDefaultGenericTemplateRules(userId: String = DEFAULT_USER_ID): List<TemplateRuleEntity> {
        val rules = mutableListOf<TemplateRuleEntity>()

        fun addSpecs(specs: List<TemplateItemSpec>, moduleKey: String) {
            for (spec in specs) {
                rules.add(
                    TemplateRuleEntity(
                        userId = userId,
                        moduleKey = moduleKey,
                        name = spec.name,
                        category = spec.category.displayName,
                        scaleByDays = false,
                        ratioPerDay = 1.0,
                        isDoorwayCritical = spec.isDoorwayCritical,
                        isHighLossReturn = spec.isHighLossReturn,
                        bagLocation = spec.bagLocation.label
                    )
                )
            }
        }

        addSpecs(ALWAYS_PACK_BASICS, "Always")
        addSpecs(DEFAULT_DOCUMENTS, "Always")
        addSpecs(DEFAULT_TECH, "Always")
        addSpecs(DEFAULT_TOILETRIES, "Always")
        addSpecs(DEFAULT_PACKING_GEAR, "Only when flying")
        addSpecs(WORK_MODULE_ITEMS, "Only when working")

        return rules
    }

    fun plugTypeForCountry(countryCode: String): String {
        return when (countryCode.uppercase()) {
            "GB", "SG" -> "Type G (UK 3-pin)"
            "JP" -> "Type A/B (100V)"
            "CH" -> "Type J (Swiss)"
            "FR", "DE", "IT", "ES" -> "Type C/E (EU 2-pin)"
            "IN" -> "Type C/D/M (230V)"
            "US", "CA", "MX" -> "Type A/B"
            else -> "Universal Adapter ($countryCode)"
        }
    }
}
