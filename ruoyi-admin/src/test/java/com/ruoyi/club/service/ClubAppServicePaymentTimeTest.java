package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

class ClubAppServicePaymentTimeTest
{
    @Test void acceptsLocalDateTimeReturnedByJdbcDriver()
    {
        LocalDateTime value = LocalDateTime.of(2026, 9, 4, 19, 30, 0);
        assertEquals(Timestamp.valueOf(value).toInstant(), ClubAppService.paymentExpiryInstant(value));
    }

    @Test void preservesSupportedTimestampRepresentations()
    {
        Instant instant = Instant.parse("2026-09-04T11:30:00Z");
        assertEquals(instant, ClubAppService.paymentExpiryInstant(Timestamp.from(instant)));
        assertEquals(instant, ClubAppService.paymentExpiryInstant(OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(8))));
        assertNull(ClubAppService.paymentExpiryInstant(null));
    }

    @Test void rejectsUnknownTimestampRepresentation()
    {
        assertThrows(ServiceException.class, () -> ClubAppService.paymentExpiryInstant("2026-09-04 19:30:00"));
    }
}
