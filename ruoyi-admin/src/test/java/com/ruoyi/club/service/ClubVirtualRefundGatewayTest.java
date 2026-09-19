package com.ruoyi.club.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubVirtualRefundGatewayTest
{
    private final VirtualPaymentTestDatabase db=new VirtualPaymentTestDatabase();
    private final ClubVirtualPayClient client=mock(ClubVirtualPayClient.class);
    private final ClubVirtualPayGateway gateway=new ClubVirtualPayGateway(db.jdbc,client);
    private final JSONObject original=new JSONObject();
    private final JSONObject refund=new JSONObject();
    @BeforeEach void setup() {
        db.payment(1,"success",7);
        original.put("order_type",7);original.put("paid_fee",9900);original.put("wx_order_id","wx-1");original.put("status",8);original.put("left_fee",0);
        refund.put("order_type",8);refund.put("refund_fee",9900);refund.put("wx_order_id","wx-refund-1");refund.put("status",8);
        when(client.query("openid-7","VP00000001",0)).thenReturn(original);
        when(client.query("openid-7","APPLE-R1",0)).thenReturn(refund);
    }
    @Test void fullRefundCanBeConfirmedFromOriginalOrderWithoutRefundId() {
        assertEquals(original,gateway.confirmedAppleRefund("VP00000001"));
        assertNull(db.jdbc.queryForObject("select refund_no from club_virtual_payment",String.class));
    }
    @Test void paidButNotRefundedIsNotARefundEvenWithZeroRemainder() {
        original.put("status",4);assertNull(gateway.confirmedAppleRefund("VP00000001"));
    }
    @Test void ordinaryChannelCannotBecomeExternalAppleRefund() {
        original.put("order_type",0);assertNull(gateway.confirmedAppleRefund("VP00000001"));
    }
    @Test void partialOrMissingRemainderIsRejected() {
        original.put("left_fee",1);assertThrows(ServiceException.class,()->gateway.confirmedAppleRefund("VP00000001"));
        original.remove("left_fee");assertThrows(ServiceException.class,()->gateway.confirmedAppleRefund("VP00000001"));
    }
    @Test void mismatchedPaidAmountRejected() {
        original.put("paid_fee",9800);assertThrows(ServiceException.class,()->gateway.confirmedAppleRefund("VP00000001"));
    }
    @Test void sandboxCannotReverseLiveMoney() {
        db.jdbc.update("update club_virtual_payment set environment=1");
        assertThrows(ServiceException.class,()->gateway.confirmedAppleRefund("VP00000001"));verifyNoInteractions(client);
    }
    @Test void callbackCandidateVerifiedWithoutPersistingItFirst() {
        assertEquals("wx-refund-1",gateway.confirmedRefund("VP00000001","AIR-1","APPLE-R1").getRefundId());
        assertNull(db.jdbc.queryForObject("select refund_no from club_virtual_payment",String.class));
    }
    @Test void mismatchedRefundChannelAndAmountAreRejected() {
        refund.put("order_type",1);assertThrows(ServiceException.class,()->gateway.confirmedRefund("VP00000001","AIR-1","APPLE-R1"));
        refund.put("order_type",8);refund.put("refund_fee",1);assertThrows(ServiceException.class,()->gateway.confirmedRefund("VP00000001","AIR-1","APPLE-R1"));
    }
}
