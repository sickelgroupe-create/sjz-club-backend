package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.ruoyi.common.exception.ServiceException;

class ClubTeenPolicyServiceTest
{
    @Test
    void disabledTeenModeDoesNotRestrictDirectApiCalls()
    {
        FakeJdbc jdbc = new FakeJdbc(false);
        ClubTeenPolicyService service = new ClubTeenPolicyService(jdbc);
        assertDoesNotThrow(() -> service.assertCanOrder(10L, new BigDecimal("999")));
        assertDoesNotThrow(() -> service.assertCanRecharge(10L));
    }

    @Test
    void enabledTeenModeBlocksOrderRechargeBalanceAndWithdrawalFromServerSide()
    {
        FakeJdbc jdbc = new FakeJdbc(true);
        jdbc.config.put("teen_allowed_start", "00:00");
        jdbc.config.put("teen_allowed_end", "00:00");
        jdbc.config.put("teen_block_order", "1");
        jdbc.config.put("teen_block_recharge", "1");
        jdbc.config.put("teen_block_balance_payment", "1");
        jdbc.config.put("teen_block_withdrawal", "1");
        ClubTeenPolicyService service = new ClubTeenPolicyService(jdbc);
        assertThrows(ServiceException.class, () -> service.assertCanOrder(10L, BigDecimal.ONE));
        assertThrows(ServiceException.class, () -> service.assertCanRecharge(10L));
        assertThrows(ServiceException.class, () -> service.assertCanPay(10L, BigDecimal.ONE, "balance"));
        assertThrows(ServiceException.class, () -> service.assertCanWithdraw(10L));
    }

    @Test
    void dailyAndSingleLimitsAreAppliedAfterServerSideHistoryLookup()
    {
        FakeJdbc jdbc = new FakeJdbc(true);
        jdbc.config.put("teen_allowed_start", "00:00");
        jdbc.config.put("teen_allowed_end", "00:00");
        jdbc.config.put("teen_block_order", "0");
        jdbc.config.put("teen_single_spend_limit", "50");
        jdbc.config.put("teen_daily_spend_limit", "100");
        jdbc.usedToday = new BigDecimal("60");
        ClubTeenPolicyService service = new ClubTeenPolicyService(jdbc);
        assertThrows(ServiceException.class, () -> service.assertCanOrder(10L, new BigDecimal("51")));
        assertThrows(ServiceException.class, () -> service.assertCanOrder(10L, new BigDecimal("41")));
        assertDoesNotThrow(() -> service.assertCanOrder(10L, new BigDecimal("40")));
    }

    @Test
    void contentIncludesRankingOnlyInsideConfiguredAllowedWindow()
    {
        FakeJdbc jdbc = new FakeJdbc(true);
        jdbc.config.put("teen_allowed_start", "00:00");
        jdbc.config.put("teen_allowed_end", "00:00");
        jdbc.config.put("teen_content_scope", "product,news,ranking");
        ClubTeenPolicyService service = new ClubTeenPolicyService(jdbc);
        assertDoesNotThrow(() -> service.assertContentAllowed(10L, "ranking"));
        assertThrows(ServiceException.class, () -> service.assertContentAllowed(10L, "promotion"));
    }

    private static class FakeJdbc extends JdbcTemplate
    {
        private final boolean enabled;
        private final Map<String, String> config = new HashMap<>();
        private BigDecimal usedToday = BigDecimal.ZERO;

        FakeJdbc(boolean enabled) { this.enabled = enabled; }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args)
        {
            Object value = sql.contains("club_teen_setting") ? Integer.valueOf(enabled ? 1 : 0) : usedToday;
            return requiredType.cast(value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args)
        {
            String key = String.valueOf(args[0]);
            String value = config.get(key);
            return value == null ? Collections.emptyList() : Collections.singletonList((T) value);
        }
    }
}
