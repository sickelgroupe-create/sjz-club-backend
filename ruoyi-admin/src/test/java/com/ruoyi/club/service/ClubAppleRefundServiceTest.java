package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubAppleRefundServiceTest
{
    private final VirtualPaymentTestDatabase db=new VirtualPaymentTestDatabase();
    private final ClubVirtualPayGateway gateway=mock(ClubVirtualPayGateway.class);
    private final ClubBusinessService business=new ClubBusinessService(db.jdbc,mock(ClubCatalogService.class));
    private final ClubAppleRefundService service=new ClubAppleRefundService(db.jdbc,gateway,business);
    private final String no="VP00000001";

    @BeforeEach void setup() {
        db.appleOrder();
        JSONObject paid=new JSONObject();paid.put("paid_fee",9900);paid.put("status",8);paid.put("order_type",7);paid.put("left_fee",0);paid.put("wx_order_id","wx-1");
        when(gateway.confirmedAppleRefund(no)).thenReturn(paid);
    }
    private void reconcile() { db.transaction.executeWithoutResult(s->service.reconcile(no)); }
    private String paymentStatus() { return db.jdbc.queryForObject("select status from club_payment where id=1",String.class); }

    @Test void externalRefundWithoutLocalApplicationReversesMoneyAndCreatesAuditedCase() {
        reconcile();
        assertEquals("refunded",paymentStatus());
        assertEquals("refunded",db.jdbc.queryForObject("select status from club_order where id=1",String.class));
        assertEquals("refunded",db.jdbc.queryForObject("select status from club_aftersale",String.class));
        assertNull(db.jdbc.queryForObject("select reviewed_by from club_aftersale",Long.class));
        assertEquals("apple",db.jdbc.queryForObject("select operator_type from club_aftersale_log",String.class));
        assertEquals(new BigDecimal("0.00"),db.jdbc.queryForObject("select balance from club_wallet where user_id=9",BigDecimal.class));
        assertEquals(new BigDecimal("50.00"),db.jdbc.queryForObject("select amount from club_provider_receivable",BigDecimal.class));
        assertEquals(11,db.jdbc.queryForObject("select stock from club_product_sku where id=11",Integer.class));
        assertNull(db.jdbc.queryForObject("select wechat_refund_id from club_payment",String.class),"Polling must not fabricate a refund transaction ID");
        assertTrue(db.jdbc.queryForObject("select content from club_message",String.class).contains("Apple"));
    }
    @Test void repeatedPollingIsIdempotentForWalletStockAndReceivables() {
        reconcile();reconcile();reconcile();
        assertEquals(1,db.jdbc.queryForObject("select count(*) from club_wallet_record",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("select count(*) from club_provider_receivable",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("select count(*) from club_aftersale_log",Integer.class));
        assertEquals(11,db.jdbc.queryForObject("select stock from club_product_sku",Integer.class));
    }
    @Test void concurrentWorkersReverseOnlyOnce() throws Exception {
        JSONObject confirmed=gateway.confirmedAppleRefund(no);
        CyclicBarrier ready=new CyclicBarrier(2);
        when(gateway.confirmedAppleRefund(no)).thenAnswer(call->{ready.await(5,TimeUnit.SECONDS);return confirmed;});
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<?> first=workers.submit(this::reconcile);
            Future<?> second=workers.submit(this::reconcile);
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals(1,db.jdbc.queryForObject("select count(*) from club_wallet_record",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("select count(*) from club_aftersale",Integer.class));
        assertEquals(11,db.jdbc.queryForObject("select stock from club_product_sku",Integer.class));
    }
    @Test void existingApprovedAppleRefundRecoversEvenWithNoRemoteRefundNumber() {
        existingCase("approved");
        db.jdbc.update("update club_payment set refund_no='AFR-1',refund_status='apple_pending'");
        reconcile();
        assertEquals("refunded",paymentStatus());
        assertEquals("AFR-1",db.jdbc.queryForObject("select refund_no from club_payment",String.class));
        assertEquals(55L,db.jdbc.queryForObject("select reviewed_by from club_aftersale",Long.class));
        assertEquals("original review",db.jdbc.queryForObject("select review_note from club_aftersale",String.class));
    }
    @Test void externalAppleDecisionDoesNotFabricateAdminApprovalAfterLocalRejection() {
        existingCase("rejected");reconcile();
        assertEquals("rejected",db.jdbc.queryForObject("select from_status from club_aftersale_log",String.class));
        assertEquals("original review",db.jdbc.queryForObject("select review_note from club_aftersale",String.class));
    }
    @Test void notConfirmedYetDoesNotMutateAnything() {
        when(gateway.confirmedAppleRefund(no)).thenReturn(null);reconcile();
        assertEquals("success",paymentStatus());
        assertEquals(0,db.jdbc.queryForObject("select count(*) from club_aftersale",Integer.class));
    }
    @Test void wrongOriginalTransactionIsRejected() {
        db.jdbc.update("update club_payment set mock_transaction_no='someone-else'");
        assertThrows(ServiceException.class,this::reconcile);
        assertEquals("success",paymentStatus());
        assertEquals(0,db.jdbc.queryForObject("select count(*) from club_aftersale",Integer.class));
    }
    @Test void sandboxRecordCannotReverseProductionMoney() {
        db.jdbc.update("update club_virtual_payment set environment=1");
        assertThrows(ServiceException.class,this::reconcile);
        assertEquals("success",paymentStatus());
    }
    @Test void localAmountMismatchRejectedWithoutChangingMoney() {
        db.jdbc.update("update club_payment set amount=98");
        assertThrows(ServiceException.class,this::reconcile);
        assertEquals(new BigDecimal("30.00"),db.jdbc.queryForObject("select balance from club_wallet",BigDecimal.class));
    }
    @Test void accountingFailureRollsBackRefundCaseAuditAndPaymentStatus() {
        db.jdbc.update("delete from club_wallet");
        assertThrows(RuntimeException.class,this::reconcile);
        assertEquals("success",paymentStatus());
        assertEquals(0,db.jdbc.queryForObject("select count(*) from club_aftersale",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("select count(*) from club_payment_audit",Integer.class));
        assertEquals("completed",db.jdbc.queryForObject("select status from club_order",String.class));
    }
    @Test void lateDuplicateCallbackAddsRealRefundIdWithoutReversingAgain() {
        reconcile();
        RefundNotification refund=new RefundNotification();refund.setRefundId("wx-refund-1");refund.setTransactionId("wx-1");
        Amount amount=new Amount();amount.setRefund(9900L);refund.setAmount(amount);
        when(gateway.confirmedRefund(no,no,"APPLE-R1")).thenReturn(refund);
        db.transaction.executeWithoutResult(s->service.confirmNotification(no,"APPLE-R1","wx-refund-1",9900L,"wx-1"));
        db.transaction.executeWithoutResult(s->service.confirmNotification(no,"APPLE-R1","wx-refund-1",9900L,"wx-1"));
        assertEquals("wx-refund-1",db.jdbc.queryForObject("select wechat_refund_id from club_payment",String.class));
        assertEquals(1,db.jdbc.queryForObject("select count(*) from club_wallet_record",Integer.class));
    }
    @Test void unconfirmedCallbackDoesNotPersistUntrustedRefundNumber() {
        when(gateway.confirmedRefund(no,no,"APPLE-R1")).thenReturn(null);
        assertThrows(ServiceException.class,()->db.transaction.executeWithoutResult(s->service.confirmNotification(no,"APPLE-R1","bad",9900L,"wx-1")));
        assertNull(db.jdbc.queryForObject("select refund_no from club_virtual_payment",String.class));
        assertEquals("success",paymentStatus());
    }
    private void existingCase(String status) {
        db.jdbc.update("insert into club_aftersale(id,aftersale_no,order_id,user_id,status,reason,refund_amount,request_id,reviewed_by,review_note) values(1,'AS1',1,7,?,'user request',99,'apply-1',55,'original review')",status);
    }
}
