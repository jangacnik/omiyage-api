package com.omiyage.backend.combine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class OutgoingCombineRequestsTest {

    private val now = OffsetDateTime.parse("2026-11-03T12:00:00Z")

    @Test
    fun `a declined request is listed until exactly 14 days after the decline`() {
        val lastListed = now.minusDays(14).plusNanos(1000)

        assertThat(isListedAsOutgoing(CombineRequestStatus.DECLINED, lastListed, now)).isTrue()
        assertThat(isListedAsOutgoing(CombineRequestStatus.DECLINED, now.minusDays(14), now)).isFalse()
        assertThat(isListedAsOutgoing(CombineRequestStatus.DECLINED, now.minusDays(15), now)).isFalse()
    }

    @Test
    fun `pending is always listed, decided requests other than declined never`() {
        assertThat(isListedAsOutgoing(CombineRequestStatus.PENDING, null, now)).isTrue()
        assertThat(isListedAsOutgoing(CombineRequestStatus.ACCEPTED, now, now)).isFalse()
        assertThat(isListedAsOutgoing(CombineRequestStatus.CANCELLED, now, now)).isFalse()
        assertThat(isListedAsOutgoing(CombineRequestStatus.DECLINED, null, now)).isFalse()
    }
}
