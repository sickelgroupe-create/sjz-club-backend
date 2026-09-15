package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;

class ClubAfterSaleServiceTest
{
    @Test
    void adminRefundDetailOnlyReadsChannelStatusWithoutIssuingAnotherRefund()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ClubBusinessService business = mock(ClubBusinessService.class);
        ClubWechatPayService wechat = mock(ClubWechatPayService.class);
        ClubAfterSaleService service = new ClubAfterSaleService(jdbc, business, wechat);
        Map<String,Object> row = new HashMap<>();
        row.put("id",3L); row.put("order_id",94L); row.put("status","refunded");
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("select a.*"), eq(3L))).thenReturn(Collections.singletonList(row));
        Map<String,Object> payment = new HashMap<>();
        payment.put("mode","wechat"); payment.put("refund_status","success");
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("select mode,status"), eq(94L))).thenReturn(Collections.singletonList(payment));
        org.junit.jupiter.api.Assertions.assertEquals(Collections.singletonList(payment),service.adminDetail(3L).get("refundPayments"));
        org.mockito.Mockito.verifyNoInteractions(business,wechat);
        verify(jdbc,never()).update(anyString(),org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void rejectsPartialRefundBeforeChangingMoney()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ClubBusinessService business = org.mockito.Mockito.mock(ClubBusinessService.class);
        ClubAfterSaleService service = new ClubAfterSaleService(jdbc, business, mock(ClubWechatPayService.class));
        Map<String, Object> aftersale = new HashMap<>();
        aftersale.put("id", 9L);
        aftersale.put("order_id", 20L);
        aftersale.put("status", "platform_reviewing");
        when(jdbc.queryForList(anyString(), eq(9L))).thenReturn(Collections.singletonList(aftersale));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(20L))).thenReturn(new BigDecimal("100.00"));
        Map<String, Object> request = new HashMap<>();
        request.put("action", "approve");
        request.put("requestId", "review-1");
        request.put("note", "审核通过");
        request.put("refundAmount", "99.99");

        assertThrows(ServiceException.class, () -> service.adminReview(1L, 9L, request));
        verify(business, never()).executeApprovedRefund(any(), any(), any(), any(), anyString());
    }

    @Test
    void providerTimeoutEscalationIsConditionalAndAudited()
    {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        ClubBusinessService business = org.mockito.Mockito.mock(ClubBusinessService.class);
        ClubAfterSaleService service = new ClubAfterSaleService(jdbc, business, mock(ClubWechatPayService.class));
        Map<String, Object> aftersale = new HashMap<>();
        aftersale.put("id", 9L);
        aftersale.put("order_id", 20L);
        aftersale.put("user_id", 2L);
        aftersale.put("provider_user_id", 3L);
        aftersale.put("status", "applied");
        when(jdbc.queryForList(anyString(), eq(9L))).thenReturn(Collections.singletonList(aftersale));
        when(jdbc.update(org.mockito.ArgumentMatchers.contains("set status='platform_reviewing'"), eq(9L))).thenReturn(1);

        service.escalateProviderTimeout(9L);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("club_aftersale_log"), eq(9L), eq("applied"), eq("platform_reviewing"), eq("system"), eq(null), anyString());
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("club_order_log"), eq(20L));
    }

    @Test
    void realWechatRefundWaitsForConfirmedCallbackBeforeLocalMoneyReversal()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ClubBusinessService business = mock(ClubBusinessService.class);
        ClubWechatPayService wechatPay = mock(ClubWechatPayService.class);
        ClubAfterSaleService service = new ClubAfterSaleService(jdbc, business, wechatPay);
        Map<String, Object> aftersale = new HashMap<>();
        aftersale.put("id", 17L); aftersale.put("order_id", 44L); aftersale.put("user_id", 8L);
        aftersale.put("status", "platform_reviewing");
        Map<String, Object> payment = new HashMap<>();
        payment.put("id", 22L); payment.put("payment_no", "WX-ORDER"); payment.put("mode", "wechat");
        payment.put("amount", new BigDecimal("12.34")); payment.put("status", "success");
        when(jdbc.queryForList(anyString(), eq(17L))).thenReturn(Collections.singletonList(aftersale));
        when(jdbc.queryForList(anyString(), eq(44L))).thenReturn(Collections.singletonList(payment));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(44L))).thenReturn(new BigDecimal("12.34"));
        Refund pending = new Refund(); pending.setStatus(Status.PROCESSING); pending.setRefundId("R-WX");
        when(wechatPay.refund(eq("WX-ORDER"), eq("AFR-17"), eq(new BigDecimal("12.34")), anyString())).thenReturn(pending);
        Map<String, Object> request = new HashMap<>();
        request.put("action", "approve"); request.put("requestId", "review-17"); request.put("note", "同意全额退款");

        service.adminReview(1L, 17L, request);

        verify(wechatPay).refund(eq("WX-ORDER"), eq("AFR-17"), eq(new BigDecimal("12.34")), anyString());
        verify(business, never()).executeApprovedRefund(any(), any(), any(), any(), anyString());
    }
}
