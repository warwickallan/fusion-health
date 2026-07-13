package com.fusionhealth.diagnostic

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the read-permission set requested in code against the six
 * `android.permission.health.READ_*` entries declared in AndroidManifest.xml, so the two can't
 * silently drift apart (as the manifest's missing rationale-activity declaration did in PR2's
 * original build, which caused every read to come back PERMISSION_DENIED on a real device).
 */
class HealthConnectPermissionsTest {

    @Test
    fun `requested read permissions match manifest declarations`() {
        val expected = setOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_NUTRITION",
            "android.permission.health.READ_WEIGHT",
            "android.permission.health.READ_BODY_FAT",
        )

        val actual = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `no write permission is ever requested`() {
        val expected = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
        )

        assertEquals(true, expected.none { it.contains("WRITE", ignoreCase = true) })
    }
}
