package com.fusionhealth.diagnostic

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards the Samsung sweep's read-permission set (derived in code from the swept record classes via
 * the real `androidx.health.connect.client` SDK) against the literal `android.permission.health.*`
 * strings declared in AndroidManifest.xml. If a manifest permission literal is wrong or a swept
 * type's permission string differs from what's assumed, this fails at CI -- before a device test --
 * with the exact expected/actual strings, rather than silently coming back PERMISSION_DENIED on the
 * device (the failure mode PR2 hit).
 */
class SamsungSweepPermissionsTest {

    // The exact permission strings declared in AndroidManifest.xml for the sweep (plus the six
    // WP1/PR2 permissions that overlap). Must stay identical to the manifest's <uses-permission>.
    private val expectedManifestReadPermissions = setOf(
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_DISTANCE",
        "android.permission.health.READ_FLOORS_CLIMBED",
        "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
        "android.permission.health.READ_TOTAL_CALORIES_BURNED",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_ELEVATION_GAINED",
        "android.permission.health.READ_SPEED",
        "android.permission.health.READ_CYCLING_PEDALING_CADENCE",
        "android.permission.health.READ_POWER",
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_RESTING_HEART_RATE",
        "android.permission.health.READ_HEART_RATE_VARIABILITY",
        "android.permission.health.READ_OXYGEN_SATURATION",
        "android.permission.health.READ_RESPIRATORY_RATE",
        "android.permission.health.READ_BLOOD_PRESSURE",
        "android.permission.health.READ_BLOOD_GLUCOSE",
        "android.permission.health.READ_BODY_TEMPERATURE",
    )

    private val sweptTypeReadPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
        HealthPermission.getReadPermission(PowerRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
    )

    @Test
    fun `swept read permissions exactly match the manifest declarations`() {
        val missingFromManifest = sweptTypeReadPermissions - expectedManifestReadPermissions
        val extraInManifest = expectedManifestReadPermissions - sweptTypeReadPermissions
        val diagnostic =
            "permissions the SDK requires but the manifest list omits: $missingFromManifest; " +
                "permissions in the manifest list the SDK does not produce: $extraInManifest"
        assertEquals(diagnostic, expectedManifestReadPermissions, sweptTypeReadPermissions)
    }

    @Test
    fun `no write permission is ever requested by the sweep`() {
        assertFalse(sweptTypeReadPermissions.any { it.contains("WRITE", ignoreCase = true) })
    }
}
