package com.ruoyi.club.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubVirtualPaymentJobTest
{
    private final VirtualPaymentTestDatabase db=new VirtualPaymentTestDatabase();
    private final ClubVirtualPayGateway gateway=mock(ClubVirtualPayGateway.class);
    private final ClubAppService app=mock(ClubAppService.class);
    private final ClubAfterSaleService afterSale=mock(ClubAfterSaleService.class);
    private final ClubAppleRefundService apple=mock(ClubAppleRefundService.class);
    private ClubVirtualPaymentJob job() { return new ClubVirtualPaymentJob(db.jdbc,gateway,app,afterSale,apple); }
    @BeforeEach void enabled() { when(gateway.enabled()).thenReturn(true); }

    @Test void hundredBrokenPaymentsCannotStarveTheNextOneEvenAfterWorkerRestart() {
        for(int i=1;i<=101;i++)db.payment(i,"created",null);
        when(app.syncWechatPayment(eq(7L),anyLong())).thenThrow(new IllegalStateException("offline"));
        Object updated=db.jdbc.queryForObject("select updated_at from club_virtual_payment where payment_no='VP00000001'",Object.class);
        job().reconcile();
        verify(app,never()).syncWechatPayment(7L,101L);
        job().reconcile(); // a new worker instance must use persisted retry ordering
        verify(app).syncWechatPayment(7L,101L);
        assertEquals(101,db.jdbc.queryForObject("select count(*) from club_virtual_payment where payment_checked_at is not null",Integer.class));
        assertEquals(updated,db.jdbc.queryForObject("select updated_at from club_virtual_payment where payment_no='VP00000001'",Object.class));
    }
    @Test void hundredBrokenRefundsCannotStarveNewAppleRefunds() {
        for(int i=1;i<=101;i++)db.payment(i,"success",7);
        db.jdbc.update("update club_virtual_payment set delivery_confirmed=1");
        doThrow(new IllegalStateException("offline")).when(apple).reconcile(anyString());
        job().reconcile();job().reconcile();
        verify(apple).reconcile("VP00000101");
        assertEquals(101,db.jdbc.queryForObject("select count(*) from club_virtual_payment where refund_checked_at is not null",Integer.class));
    }
    @Test void applePendingAndUnsolicitedRefundsAreBothPolledWithoutRefundIds() {
        db.payment(1,"success",7);db.payment(2,"success",null);
        db.jdbc.update("update club_payment set refund_status='apple_pending',refund_no='AFR-2' where id=2");
        db.jdbc.update("update club_virtual_payment set delivery_confirmed=1");
        job().reconcile();
        verify(apple).reconcile("VP00000001");verify(apple).reconcile("VP00000002");
        verify(gateway,never()).confirmedRefund(anyString(),anyString());
    }
    @Test void ordinaryRefundsRetainExistingConfirmationBoundary() {
        db.payment(1,"success",0);
        db.jdbc.update("update club_payment set refund_status='processing',refund_no='AFR-1'");
        db.jdbc.update("update club_virtual_payment set refund_no='VR000001',delivery_confirmed=1");
        job().reconcile();verify(gateway).confirmedRefund("VP00000001","AFR-1");
        verifyNoInteractions(apple,afterSale);
    }
    @Test void sandboxAndAlreadyReversedPaymentsAreNotPolled() {
        db.payment(1,"success",7);db.payment(2,"refunded",7);
        db.jdbc.update("update club_virtual_payment set environment=1 where payment_no='VP00000001'");
        job().reconcile();verifyNoInteractions(app,apple,afterSale);
        verify(gateway,never()).confirmDelivery(anyString());
    }
    @Test void disabledGatewayDoesNotStampOrCallAnything() {
        db.payment(1,"created",null);when(gateway.enabled()).thenReturn(false);job().reconcile();
        assertNull(db.jdbc.queryForObject("select payment_checked_at from club_virtual_payment",Object.class));
        verifyNoInteractions(app,apple,afterSale);
    }
}
