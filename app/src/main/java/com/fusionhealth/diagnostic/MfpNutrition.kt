package com.fusionhealth.diagnostic

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import java.time.Instant
import java.time.ZoneId

/**
 * BUILD-005/WP2 — Unified snapshot: MyFitnessPal nutrition. Logic for extracting, totalling and
 * formatting MyFitnessPal-origin NutritionRecord data read through Health Connect. The SDK
 * touch-point is [toNutritionEntry] (one conversion per record); everything downstream operates on
 * plain data classes and is directly unit-testable.
 *
 * Every non-null nutrient field the current stable NutritionRecord API supports is extracted --
 * nothing populated is silently discarded, and nothing missing is displayed as zero.
 */

/** One populated nutrient beyond the headline macros: label + amount in grams. */
internal data class NutrientAmount(val label: String, val grams: Double)

/** One MyFitnessPal nutrition record reduced to display-ready values. */
internal data class NutritionEntry(
    val start: Instant,
    val mealTypeCode: Int,
    val name: String?,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fibreG: Double?,
    val sugarG: Double?,
    val otherNutrients: List<NutrientAmount>,
)

/** Maps a Health Connect meal type code to a readable label. */
internal fun mealTypeLabel(code: Int): String = when (code) {
    MealType.MEAL_TYPE_BREAKFAST -> "Breakfast"
    MealType.MEAL_TYPE_LUNCH -> "Lunch"
    MealType.MEAL_TYPE_DINNER -> "Dinner"
    MealType.MEAL_TYPE_SNACK -> "Snack"
    else -> "Meal"
}

/**
 * Converts one real NutritionRecord to a [NutritionEntry], extracting every nutrient field the
 * current stable API supports. Headline macros are kept as named fields; every other non-null
 * nutrient goes into [NutritionEntry.otherNutrients].
 */
internal fun toNutritionEntry(record: NutritionRecord): NutritionEntry {
    val others = mutableListOf<NutrientAmount>()
    fun add(label: String, grams: Double?) {
        if (grams != null) others += NutrientAmount(label, grams)
    }
    add("Saturated fat", record.saturatedFat?.inGrams)
    add("Unsaturated fat", record.unsaturatedFat?.inGrams)
    add("Monounsaturated fat", record.monounsaturatedFat?.inGrams)
    add("Polyunsaturated fat", record.polyunsaturatedFat?.inGrams)
    add("Trans fat", record.transFat?.inGrams)
    add("Cholesterol", record.cholesterol?.inGrams)
    add("Sodium", record.sodium?.inGrams)
    add("Potassium", record.potassium?.inGrams)
    add("Calcium", record.calcium?.inGrams)
    add("Iron", record.iron?.inGrams)
    add("Magnesium", record.magnesium?.inGrams)
    add("Zinc", record.zinc?.inGrams)
    add("Phosphorus", record.phosphorus?.inGrams)
    add("Selenium", record.selenium?.inGrams)
    add("Copper", record.copper?.inGrams)
    add("Manganese", record.manganese?.inGrams)
    add("Chloride", record.chloride?.inGrams)
    add("Chromium", record.chromium?.inGrams)
    add("Molybdenum", record.molybdenum?.inGrams)
    add("Iodine", record.iodine?.inGrams)
    add("Vitamin A", record.vitaminA?.inGrams)
    add("Vitamin B6", record.vitaminB6?.inGrams)
    add("Vitamin B12", record.vitaminB12?.inGrams)
    add("Vitamin C", record.vitaminC?.inGrams)
    add("Vitamin D", record.vitaminD?.inGrams)
    add("Vitamin E", record.vitaminE?.inGrams)
    add("Vitamin K", record.vitaminK?.inGrams)
    add("Thiamin", record.thiamin?.inGrams)
    add("Riboflavin", record.riboflavin?.inGrams)
    add("Niacin", record.niacin?.inGrams)
    add("Folate", record.folate?.inGrams)
    add("Folic acid", record.folicAcid?.inGrams)
    add("Biotin", record.biotin?.inGrams)
    add("Pantothenic acid", record.pantothenicAcid?.inGrams)
    add("Caffeine", record.caffeine?.inGrams)
    record.energyFromFat?.inKilocalories?.let { others += NutrientAmount("Energy from fat (kcal)", it) }

    return NutritionEntry(
        start = record.startTime,
        mealTypeCode = record.mealType,
        name = record.name,
        caloriesKcal = record.energy?.inKilocalories,
        proteinG = record.protein?.inGrams,
        carbsG = record.totalCarbohydrate?.inGrams,
        fatG = record.totalFat?.inGrams,
        fibreG = record.dietaryFiber?.inGrams,
        sugarG = record.sugar?.inGrams,
        otherNutrients = others,
    )
}

/** Macro totals over one day window; a field is null only if NO entry in the window carried it. */
internal data class MacroTotals(
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fibreG: Double?,
    val sugarG: Double?,
)

/** Entries whose start time falls in [dayStart, nextDayStart). */
internal fun entriesInDay(entries: List<NutritionEntry>, dayStart: Instant, nextDayStart: Instant): List<NutritionEntry> =
    entries.filter { !it.start.isBefore(dayStart) && it.start.isBefore(nextDayStart) }

private fun sumOrNull(values: List<Double?>): Double? {
    val present = values.filterNotNull()
    return if (present.isEmpty()) null else present.sum()
}

/** Sums headline macros for the given entries; null when there are no entries at all. */
internal fun macroTotals(entries: List<NutritionEntry>): MacroTotals? {
    if (entries.isEmpty()) return null
    return MacroTotals(
        caloriesKcal = sumOrNull(entries.map { it.caloriesKcal }),
        proteinG = sumOrNull(entries.map { it.proteinG }),
        carbsG = sumOrNull(entries.map { it.carbsG }),
        fatG = sumOrNull(entries.map { it.fatG }),
        fibreG = sumOrNull(entries.map { it.fibreG }),
        sugarG = sumOrNull(entries.map { it.sugarG }),
    )
}

/** Sums every non-headline nutrient across the given entries, by label. */
internal fun sumOtherNutrients(entries: List<NutritionEntry>): List<NutrientAmount> =
    entries.flatMap { it.otherNutrients }
        .groupBy { it.label }
        .map { (label, amounts) -> NutrientAmount(label, amounts.sumOf { it.grams }) }
        .sortedBy { it.label }

/** Everything the Nutrition section needs. */
internal data class NutritionData(
    val status: SourceReadStatus,
    val todayTotals: MacroTotals?,
    val yesterdayTotals: MacroTotals?,
    val todayMeals: List<NutritionEntry>,
    val todayOtherNutrients: List<NutrientAmount>,
    val latestRecordTime: Instant?,
    val errorMessage: String? = null,
)

private fun g1(v: Double): String = String.format(java.util.Locale.US, "%.1f g", v)
private fun kc(v: Double): String = String.format(java.util.Locale.US, "%.0f kcal", v)

/** Adaptive mass format: grams when >= 0.5 g, otherwise milligrams -- so 0.012 g reads 12.0 mg. */
internal fun formatNutrientGrams(grams: Double): String =
    if (grams >= 0.5) g1(grams)
    else String.format(java.util.Locale.US, "%.1f mg", grams * 1000.0)

private fun macroLine(label: String, totals: MacroTotals?): String {
    if (totals == null) return "• $label: EMPTY (no MyFitnessPal records in this period)"
    val na = "Not available"
    return "• $label: ${totals.caloriesKcal?.let { kc(it) } ?: na} — " +
        "P ${totals.proteinG?.let { g1(it) } ?: na}, " +
        "C ${totals.carbsG?.let { g1(it) } ?: na}, " +
        "F ${totals.fatG?.let { g1(it) } ?: na}, " +
        "fibre ${totals.fibreG?.let { g1(it) } ?: na}, " +
        "sugar ${totals.sugarG?.let { g1(it) } ?: na}"
}

/** Formats the MyFitnessPal Nutrition section — calories/macros first, then meals, then details. */
internal fun formatNutrition(
    data: NutritionData?,
    timeFormatter: (Instant) -> String,
): String {
    val sb = StringBuilder()
    sb.appendLine("NUTRITION — MyFitnessPal")
    if (data == null) {
        sb.appendLine("• Not available")
        return sb.toString()
    }
    when (data.status) {
        SourceReadStatus.PERMISSION_DENIED -> {
            sb.appendLine("• PERMISSION_DENIED — nutrition read access was not granted")
            return sb.toString()
        }
        SourceReadStatus.READ_ERROR -> {
            sb.appendLine("• READ_ERROR (${data.errorMessage ?: "unknown"})")
            return sb.toString()
        }
        SourceReadStatus.EMPTY -> {
            sb.appendLine("• EMPTY — no MyFitnessPal nutrition records found")
            return sb.toString()
        }
        else -> Unit
    }
    sb.appendLine(macroLine("Today", data.todayTotals))
    sb.appendLine(macroLine("Yesterday", data.yesterdayTotals))
    sb.appendLine("• Latest record: ${data.latestRecordTime?.let { timeFormatter(it) } ?: "Not available"}")

    sb.appendLine("Today's meals:")
    if (data.todayMeals.isEmpty()) {
        sb.appendLine("  (none logged today)")
    } else {
        for (m in data.todayMeals.sortedBy { it.start }) {
            val na = "Not available"
            val label = mealTypeLabel(m.mealTypeCode)
            val name = m.name?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            sb.appendLine(
                "  ${timeFormatter(m.start)} $label$name: " +
                    "${m.caloriesKcal?.let { kc(it) } ?: na}, " +
                    "P ${m.proteinG?.let { g1(it) } ?: na}, " +
                    "C ${m.carbsG?.let { g1(it) } ?: na}, " +
                    "F ${m.fatG?.let { g1(it) } ?: na}"
            )
        }
    }

    if (data.todayOtherNutrients.isNotEmpty()) {
        sb.appendLine("All nutrition details (today):")
        for (n in data.todayOtherNutrients) {
            sb.appendLine("  ${n.label}: ${if (n.label.endsWith("(kcal)")) kc(n.grams) else formatNutrientGrams(n.grams)}")
        }
    }
    return sb.toString()
}
