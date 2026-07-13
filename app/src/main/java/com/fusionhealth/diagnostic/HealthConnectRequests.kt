package com.fusionhealth.diagnostic

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Builds a Health Connect [ReadRecordsRequest] for one page of a record type. The first page
 * ([pageToken] `== null`) must omit the page token from the request entirely rather than passing
 * an empty string — this is the exact SDK boundary that broke on Warwick's third device test:
 * passing `pageToken = ""` throws `NumberFormatException: For input string: ""` before any
 * record is read, on every single call, for every record type. Compiled and unit-tested directly
 * against the real `androidx.health.connect.client` classes (not a fake), so this construction
 * mistake cannot silently pass on fake test doubles again.
 */
internal fun <T : Record> buildReadRecordsRequest(
    recordType: KClass<T>,
    pageToken: String?,
): ReadRecordsRequest<T> = if (pageToken != null) {
    ReadRecordsRequest(
        recordType = recordType,
        timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
        pageToken = pageToken,
    )
} else {
    ReadRecordsRequest(
        recordType = recordType,
        timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
    )
}
