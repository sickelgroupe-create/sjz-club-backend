package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;

class ClubRoleLifecycleServiceTest
{
    @Test
    void activeOrderBlocksRoleSwitch()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ClubRoleLifecycleService service = new ClubRoleLifecycleService(jdbc);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("from club_order"), eq(Integer.class), eq(77L))).thenReturn(1);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("from club_aftersale"), eq(Integer.class), eq(77L))).thenReturn(0);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("from club_withdrawal"), eq(Integer.class), eq(77L))).thenReturn(0);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("from club_provider_receivable"), eq(Integer.class), eq(77L))).thenReturn(0);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("from club_wallet"), eq(BigDecimal.class), eq(77L))).thenReturn(BigDecimal.ZERO);

        assertThrows(ServiceException.class, () -> service.assertSwitchable(77L));
    }
}
