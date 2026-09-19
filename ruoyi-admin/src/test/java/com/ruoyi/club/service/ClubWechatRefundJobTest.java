package com.ruoyi.club.service;

import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubWechatRefundJobTest
{
    private final VirtualPaymentTestDatabase db=new VirtualPaymentTestDatabase();
    private final ClubWechatPayService wechat=mock(ClubWechatPayService.class);
    private final ClubAfterSaleService afterSale=mock(ClubAfterSaleService.class);

    @BeforeEach void setup()
    {
        db.jdbc.execute("alter table club_payment add column refund_checked_at timestamp(6)");
        when(wechat.enabled()).thenReturn(true);
    }

    private void payment(int id)
    {
        db.payment(id,"success",0);
        db.jdbc.update("update club_payment set payment_no=?,refund_no=?,refund_status='pending' where id=?","WX"+id,"AFR-"+id,id);
        db.jdbc.update("insert into club_aftersale(id,aftersale_no,order_id,user_id,status,refund_idempotency_key) values(?,?,?,7,'approved',?)",id,"AS"+id,id,"AFR-"+id);
    }

    private Refund receipt(Status status)
    {
        Refund result=new Refund();result.setOutTradeNo("WX1");result.setOutRefundNo("AFR-1");
        result.setTransactionId("wx-1");result.setRefundId("wx-refund-1");result.setStatus(status);
        Amount amount=new Amount();amount.setTotal(9900L);amount.setRefund(9900L);amount.setCurrency("CNY");result.setAmount(amount);
        return result;
    }

    private ClubWechatRefundJob job() { return new ClubWechatRefundJob(db.jdbc,wechat,afterSale); }

    @Test void acceptanceNeverCompletesTheLocalRefund()
    {
        payment(1);
        when(wechat.refund(eq("WX1"),eq("AFR-1"),any(),anyString())).thenReturn(receipt(Status.PROCESSING));
        job().reconcile();
        assertEquals("processing",db.jdbc.queryForObject("select refund_status from club_payment",String.class));
        assertEquals("success",db.jdbc.queryForObject("select status from club_payment",String.class));
        verifyNoInteractions(afterSale);
    }

    @Test void aTimeoutAfterRemoteSuccessIsRecoveredByQueryAfterRestart()
    {
        payment(1);
        when(wechat.queryRefund("AFR-1")).thenReturn(null,receipt(Status.SUCCESS));
        when(wechat.refund(eq("WX1"),eq("AFR-1"),any(),anyString())).thenThrow(new ServiceException("simulated remote timeout"));
        job().reconcile();
        assertEquals("pending",db.jdbc.queryForObject("select refund_status from club_payment",String.class));
        verifyNoInteractions(afterSale);
        job().reconcile();
        ArgumentCaptor<RefundNotification> captured=ArgumentCaptor.forClass(RefundNotification.class);
        verify(afterSale).completeWechatRefund(captured.capture());
        assertEquals("AFR-1",captured.getValue().getOutRefundNo());
        assertEquals(9900L,captured.getValue().getAmount().getRefund());
        verify(wechat,times(1)).refund(eq("WX1"),eq("AFR-1"),any(),anyString());
    }

    @Test void unverifiedQueryResponseCannotReverseLocalMoney()
    {
        payment(1);
        Refund wrong=receipt(Status.SUCCESS);wrong.getAmount().setRefund(1L);
        when(wechat.queryRefund("AFR-1")).thenReturn(wrong);
        job().reconcile();
        verifyNoInteractions(afterSale);
        verify(wechat,never()).refund(anyString(),anyString(),any(),anyString());
        assertEquals("pending",db.jdbc.queryForObject("select refund_status from club_payment",String.class));
    }

    @Test void abnormalCallbackRemainsQueryableWhenTheLaterSuccessCallbackIsLost()
    {
        payment(1);
        RefundNotification abnormal=new RefundNotification();
        abnormal.setOutRefundNo("AFR-1");abnormal.setRefundId("wx-refund-1");abnormal.setRefundStatus(Status.ABNORMAL);
        ClubBusinessService business=mock(ClubBusinessService.class);
        ClubAfterSaleService receiver=new ClubAfterSaleService(db.jdbc,business,wechat);
        db.transaction.execute(status -> { receiver.completeWechatRefund(abnormal);return null; });
        assertEquals("abnormal",db.jdbc.queryForObject("select refund_status from club_payment",String.class));
        verifyNoInteractions(business);

        when(wechat.queryRefund("AFR-1")).thenReturn(receipt(Status.SUCCESS));
        job().reconcile();
        ArgumentCaptor<RefundNotification> captured=ArgumentCaptor.forClass(RefundNotification.class);
        verify(afterSale).completeWechatRefund(captured.capture());
        assertEquals("AFR-1",captured.getValue().getOutRefundNo());
        assertEquals("wx-refund-1",captured.getValue().getRefundId());
        assertEquals(Status.SUCCESS,captured.getValue().getRefundStatus());
        verify(wechat,never()).refund(anyString(),anyString(),any(),anyString());
    }

    @Test void abnormalRefundNeverSubmitsAgainEvenWhenTheQueryReturnsMissing()
    {
        payment(1);
        db.jdbc.update("update club_payment set refund_status='abnormal'");
        when(wechat.queryRefund("AFR-1")).thenReturn(null,receipt(Status.ABNORMAL));
        job().reconcile();
        job().reconcile();
        verify(wechat,times(2)).queryRefund("AFR-1");
        verify(wechat,never()).refund(anyString(),anyString(),any(),anyString());
        verifyNoInteractions(afterSale);
        assertEquals("abnormal",db.jdbc.queryForObject("select refund_status from club_payment",String.class));
        assertEquals("success",db.jdbc.queryForObject("select status from club_payment",String.class));
    }

    @Test void closedRefundRemainsExcludedFromAutomaticReconciliation()
    {
        payment(1);
        db.jdbc.update("update club_payment set refund_status='closed'");
        job().reconcile();
        verify(wechat,never()).queryRefund(anyString());
        verify(wechat,never()).refund(anyString(),anyString(),any(),anyString());
        verifyNoInteractions(afterSale);
    }

    @Test void oneHundredBrokenRefundsCannotStarveTheNextIntent()
    {
        for(int id=1;id<=101;id++)payment(id);
        when(wechat.queryRefund(anyString())).thenThrow(new ServiceException("simulated query outage"));
        job().reconcile();
        verify(wechat,never()).queryRefund("AFR-101");
        job().reconcile();
        verify(wechat).queryRefund("AFR-101");
        verify(wechat,never()).refund(anyString(),anyString(),any(),anyString());
    }

    @Test void virtualPaymentsAndDisabledNativeGatewayAreNotSent()
    {
        payment(1);
        db.jdbc.update("update club_payment set payment_no='VP00000001'");
        job().reconcile();
        verify(wechat,never()).queryRefund(anyString());
        db.jdbc.update("update club_payment set payment_no='WX1'");
        when(wechat.enabled()).thenReturn(false);
        job().reconcile();
        verify(wechat,never()).queryRefund(anyString());
    }
}
