package com.ruoyi.club.service;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;

class ClubWalletAdminServiceTest {
    @Test void moneyIsPositiveExactAndBounded(){
        assertEquals(new BigDecimal("12.30"),ClubWalletAdminService.amount("12.3"));
        for(String value:new String[]{"0","-1","0.001","NaN","10000000000","abc"})
            assertThrows(ServiceException.class,()->ClubWalletAdminService.amount(value));
    }
    @Test void datesAreStrictAndIncludeLeapDays(){
        assertEquals("2024-02-29",ClubWalletAdminService.date("2024-02-29").toString());
        assertNull(ClubWalletAdminService.date(""));
        for(String value:new String[]{"2023-02-29","2026-9-3","2026-09-03 00:00:00","9999-12-31"})
            assertThrows(ServiceException.class,()->ClubWalletAdminService.date(value));
    }
}
