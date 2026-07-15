package com.fusionhealth.diagnostic

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Coverage for [MfpNutrition]: non-null field extraction from a real NutritionRecord (so a renamed
 * SDK field fails here, not on the device), today/yesterday day boundaries, macro totals, other-
 * nutrient summing, meal formatting, and honest missing-value handling.
 */
class MfpNutritionTest {

    private val dayStart = Instant.parse("2026-07-14T00:00:00Z")
    private val yesterdayStart = Instant.parse("2026-07-13T00:00:00Z")

    private fun entry(
        start: Instant,
        kcal: Double? = null,
        protein: Double? = null,
        carbs: Double? = null,
        fat: Double? = null,
        fibre: Double? = null,
        sugar: Double? = null,
        name: String? = null,
        mealType: Int = MealType.MEAL_TYPE_UNKNOWN,
        others: List<NutrientAmount> = emptyList(),
    ) = NutritionEntry(start, mealType, name, kcal, protein, carbs, fat, fibre, sugar, others)

    @Test
    fun `extracts every populated field from a real NutritionRecord`() {
        val record = NutritionRecord(
            startTime = Instant.parse("2026-07-14T12:00:00Z"),
            startZoneOffset = null,
            endTime = Instant.parse("2026-07-14T12:30:00Z"),
            endZoneOffset = null,
            energy = Energy.kilocalories(520.0),
            protein = Mass.grams(32.0),
            totalCarbohydrate = Mass.grams(45.0),
            totalFat = Mass.grams(18.0),
            dietaryFiber = Mass.grams(6.0),
            sugar = Mass.grams(9.0),
            sodium = Mass.grams(1.2),
            vitaminC = Mass.grams(0.045),
            name = "Chicken wrap",
            mealType = MealType.MEAL_TYPE_LUNCH,
            metadata = Metadata.unknownRecordingMethod(),
        )

        val entry = toNutritionEntry(record)

        assertEquals(520.0, entry.caloriesKcal!!, 0.0001)
        assertEquals(32.0, entry.proteinG!!, 0.0001)
        assertEquals(45.0, entry.carbsG!!, 0.0001)
        assertEquals(18.0, entry.fatG!!, 0.0001)
        assertEquals(6.0, entry.fibreG!!, 0.0001)
        assertEquals(9.0, entry.sugarG!!, 0.0001)
        assertEquals("Chicken wrap", entry.name)
        assertEquals(MealType.MEAL_TYPE_LUNCH, entry.mealTypeCode)
        // Populated non-headline nutrients are kept; absent ones are not invented.
        val labels = entry.otherNutrients.map { it.label }
        assertTrue(labels.contains("Sodium"))
        assertTrue(labels.contains("Vitamin C"))
        assertEquals(2, entry.otherNutrients.size)
    }

    @Test
    fun `day boundaries separate today from yesterday`() {
        val entries = listOf(
            entry(Instant.parse("2026-07-13T09:00:00Z"), kcal = 300.0),
            entry(Instant.parse("2026-07-13T23:59:59Z"), kcal = 200.0),
            entry(Instant.parse("2026-07-14T00:00:00Z"), kcal = 400.0),
            entry(Instant.parse("2026-07-14T10:00:00Z"), kcal = 100.0),
        )
        val today = entriesInDay(entries, dayStart, Instant.parse("2026-07-15T00:00:00Z"))
        val yesterday = entriesInDay(entries, yesterdayStart, dayStart)

        assertEquals(2, today.size)
        assertEquals(500.0, macroTotals(today)!!.caloriesKcal!!, 0.0001)
        assertEquals(2, yesterday.size)
        assertEquals(500.0, macroTotals(yesterday)!!.caloriesKcal!!, 0.0001)
    }

    @Test
    fun `totals are null when no entries exist and per-field null when never populated`() {
        assertNull(macroTotals(emptyList()))
        val totals = macroTotals(listOf(entry(dayStart, kcal = 250.0)))!!
        assertEquals(250.0, totals.caloriesKcal!!, 0.0001)
        assertNull(totals.proteinG)
    }

    @Test
    fun `other nutrients sum by label across the day`() {
        val entries = listOf(
            entry(dayStart, others = listOf(NutrientAmount("Sodium", 1.0), NutrientAmount("Iron", 0.008))),
            entry(dayStart.plusSeconds(3600), others = listOf(NutrientAmount("Sodium", 0.5))),
        )
        val summed = sumOtherNutrients(entries)
        assertEquals(listOf("Iron", "Sodium"), summed.map { it.label })
        assertEquals(1.5, summed.first { it.label == "Sodium" }.grams, 0.0001)
    }

    @Test
    fun `small amounts format as milligrams and large as grams`() {
        assertEquals("1.5 g", formatNutrientGrams(1.5))
        assertEquals("45.0 mg", formatNutrientGrams(0.045))
    }

    @Test
    fun `meal list formats type label, name, time and macros with Not available fallbacks`() {
        val data = NutritionData(
            status = SourceReadStatus.POPULATED,
            todayTotals = macroTotals(
                listOf(entry(dayStart, kcal = 520.0, protein = 32.0, carbs = 45.0, fat = 18.0, fibre = 6.0, sugar = 9.0))
            ),
            yesterdayTotals = null,
            todayMeals = listOf(
                entry(
                    Instant.parse("2026-07-14T12:00:00Z"), kcal = 520.0, protein = 32.0,
                    name = "Chicken wrap", mealType = MealType.MEAL_TYPE_LUNCH,
                ),
            ),
            todayOtherNutrients = listOf(NutrientAmount("Sodium", 1.2)),
            latestRecordTime = Instant.parse("2026-07-14T12:00:00Z"),
        )

        val text = formatNutrition(data) { "T" }

        assertTrue(text.contains("Today: 520 kcal"))
        assertTrue(text.contains("Yesterday: EMPTY"))
        assertTrue(text.contains("Lunch — Chicken wrap: 520 kcal"))
        assertTrue(text.contains("C Not available"))
        assertTrue(text.contains("All nutrition details (today):"))
        assertTrue(text.contains("Sodium: 1.2 g"))
    }

    @Test
    fun `permission denial and emptiness are reported distinctly`() {
        val denied = NutritionData(SourceReadStatus.PERMISSION_DENIED, null, null, emptyList(), emptyList(), null)
        val empty = NutritionData(SourceReadStatus.EMPTY, null, null, emptyList(), emptyList(), null)
        assertTrue(formatNutrition(denied) { "T" }.contains("PERMISSION_DENIED"))
        assertTrue(formatNutrition(empty) { "T" }.contains("EMPTY"))
    }

    @Test
    fun `meal type codes map to readable labels`() {
        assertEquals("Breakfast", mealTypeLabel(MealType.MEAL_TYPE_BREAKFAST))
        assertEquals("Dinner", mealTypeLabel(MealType.MEAL_TYPE_DINNER))
        assertEquals("Meal", mealTypeLabel(MealType.MEAL_TYPE_UNKNOWN))
    }
}
