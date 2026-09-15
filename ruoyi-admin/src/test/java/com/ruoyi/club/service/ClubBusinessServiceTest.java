package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;

class ClubBusinessServiceTest
{
    @Test
    void platformCompletionRecordsReceiptWithoutCreditingAnyProviderWallet()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ClubBusinessService business = new ClubBusinessService(jdbc, org.mockito.Mockito.mock(ClubCatalogService.class));
        Map<String, Object> order = new HashMap<>();
        order.put("id", 10L); order.put("user_id", 7L); order.put("status", "serving");
        order.put("provider_type", "platform"); order.put("total_amount", new BigDecimal("19.90"));
        when(jdbc.queryForList("select * from club_order where id=? for update", 10L)).thenReturn(Collections.singletonList(order));
        Map<String, Object> completed = new HashMap<>(order); completed.put("status", "completed");
        when(jdbc.queryForMap("select * from club_order where id=? for update", 10L)).thenReturn(completed);
        when(jdbc.queryForMap("select experience from club_user where id=? for update", 7L)).thenReturn(Collections.singletonMap("experience", 0));
        business.adminTransition(10L, "completed", "平台完成服务", 1L);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("null,'platform'"), org.mockito.ArgumentMatchers.anyString(), eq(10L), eq(new BigDecimal("19.90")), eq(new BigDecimal("19.90")));
        org.junit.jupiter.api.Assertions.assertFalse(org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .anyMatch(call -> call.getArguments().length > 0 && String.valueOf(call.getArguments()[0]).contains("club_wallet")));
    }

    @Test
    void experienceLevelsUseDocumentedBoundaries()
    {
        assertEquals("LV.1 初入江湖", ClubBusinessService.levelFor(99));
        assertEquals("LV.2 渐入佳境", ClubBusinessService.levelFor(100));
        assertEquals("LV.3 独当一面", ClubBusinessService.levelFor(500));
        assertEquals("LV.4 名震一方", ClubBusinessService.levelFor(1500));
        assertEquals("LV.5 巅峰王者", ClubBusinessService.levelFor(5000));
    }

    @Test
    void rejectsRefundThatIsNotExactlyPaidAmount()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ClubCatalogService catalog = org.mockito.Mockito.mock(ClubCatalogService.class);
        ClubBusinessService service = new ClubBusinessService(jdbc, catalog);
        Map<String, Object> order = new HashMap<>();
        order.put("id", 10L);
        order.put("status", "refunding");
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("from club_order where id=? for update"), eq(10L))).thenReturn(Collections.singletonList(order));
        Map<String, Object> payment = new HashMap<>();
        payment.put("id", 11L);
        payment.put("amount", new BigDecimal("100.00"));
        payment.put("mode", "mock_wechat");
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("from club_payment"), eq(10L))).thenReturn(Collections.singletonList(payment));

        assertThrows(ServiceException.class, () -> service.executeApprovedRefund(10L, 1L, 7L, new BigDecimal("50.00"), "test"));
    }

    @Test
    void releasedIncomeRecoversOldestReceivableBeforeWalletBalance()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ClubCatalogService catalog = org.mockito.Mockito.mock(ClubCatalogService.class);
        ClubBusinessService service = new ClubBusinessService(jdbc, catalog);
        Map<String, Object> settlement = new HashMap<>();
        settlement.put("id", 8L);
        settlement.put("provider_user_id", 4L);
        settlement.put("provider_income", new BigDecimal("80.00"));
        settlement.put("reversed_income", BigDecimal.ZERO);
        settlement.put("settlement_no", "ST8");
        settlement.put("order_id", 12L);
        settlement.put("provider_type", "player");
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("club_order_settlement where id=?"), eq(8L))).thenReturn(Collections.singletonList(settlement));
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("balance", new BigDecimal("10.00"));
        wallet.put("frozen", new BigDecimal("80.00"));
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("club_wallet where user_id=?"), eq(4L))).thenReturn(wallet);
        Map<String, Object> debt = new HashMap<>();
        debt.put("id", 3L);
        debt.put("amount", new BigDecimal("30.00"));
        debt.put("recovered_amount", BigDecimal.ZERO);
        debt.put("aftersale_id", 9L);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("club_provider_receivable"), eq(4L))).thenReturn(Collections.singletonList(debt));
        when(jdbc.update(org.mockito.ArgumentMatchers.contains("update club_provider_receivable"), eq(new BigDecimal("30.00")), eq(new BigDecimal("30.00")), eq(3L), eq(new BigDecimal("30.00")))).thenReturn(1);

        service.releaseProtectedSettlement(8L);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("insert into club_provider_receivable_recovery"), org.mockito.ArgumentMatchers.any(), eq(3L), eq(4L), eq(8L), eq(12L), eq(9L), eq(new BigDecimal("30.00")));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update club_wallet set balance=?"), eq(new BigDecimal("60.00")), eq(new BigDecimal("0.00")), eq(4L));
    }
}
