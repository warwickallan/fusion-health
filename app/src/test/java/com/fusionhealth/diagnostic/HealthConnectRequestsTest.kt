package com.fusionhealth.diagnostic

import androidx.health.connect.client.records.StepsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises [buildReadRecordsRequest] against the real `androidx.health.connect.client`
 * `ReadRecordsRequest` class -- not a fake -- so the exact defect found on Warwick's third
 * device test (passing pageToken = "" for the first page, which the real SDK rejects with
 * `NumberFormatException: For input string: ""`) is caught by a test that actually constructs
 * the real request object, not only by tests against [accumulatePages]'s fake page source.
 */
class HealthConnectRequestsTest {

    @Test
    fun `first page request omits the page token rather than passing an empty string`() {
        val request = buildReadRecordsRequest(StepsRecord::class, pageToken = null)

        // The real request must not carry an empty-string token: that's exactly what caused
        // every record type to fail identically with "For input string: \"\"" on-device.
        assertNotEquals("", request.pageToken)
        assertNull(request.pageToken)
    }

    @Test
    fun `subsequent page request carries the exact continuation token supplied`() {
        val request = buildReadRecordsRequest(StepsRecord::class, pageToken = "token-1")

        assertEquals("token-1", request.pageToken)
    }
}
