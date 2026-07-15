package com.fusionhealth.diagnostic

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards the unified snapshot's read-permission set (derived from its record classes via the real
 * SDK) against the literal permission strings declared in AndroidManifest.xml, so a wrong or
 * missing manifest permission fails at CI rather than silently returning PERMISSION_DENIED
 * on-device.
 */
class SamsungSnapshotPermissionsTest {

    private val expectedManifestReadPermissions = setOf(
        // Samsung sections
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_DISTANCE",
        "android.permission.health.READ_TOTAL_CALORIES_BURNED",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_SPEED",
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_OXYGEN_SATURATION",
        // Withings body composition
        "android.permission.health.READ_WEIGHT",
        "android.permission.health.READ_BODY_FAT",
        "android.permission.health.READ_LEAN_BODY_MASS",
        "android.permission.health.READ_BODY_WATER_MASS",
        "android.permission.health.READ_BONE_MASS",
        "android.permission.health.READ_BASAL_METABOLIC_RATE",
        "android.permission.health.READ_HEIGHT",
        // MyFitnessPal nutrition
        "android.permission.health.READ_NUTRITION",
    )

    private val snapshotReadPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
    )

    @Test
    fun `snapshot read permissions exactly match the manifest declarations`() {
        val missingFromManifest = snapshotReadPermissions - expectedManifestReadPermissions
        val extraInManifest = expectedManifestReadPermissions - snapshotReadPermissions
        val diagnostic =
            "SDK requires but manifest list omits: $missingFromManifest; " +
                "manifest list has but SDK does not produce: $extraInManifest"
        assertEquals(diagnostic, expectedManifestReadPermissions, snapshotReadPermissions)
    }

    @Test
    fun `no write permission is ever requested by the snapshot`() {
        assertFalse(snapshotReadPermissions.any { it.contains("WRITE", ignoreCase = true) })
    }
}
