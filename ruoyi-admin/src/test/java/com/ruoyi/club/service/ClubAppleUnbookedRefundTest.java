package com.ruoyi.club.service;

import java.util.HashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubAppleUnbookedRefundTest
{
    private final VirtualPaymentTestDatabase db = new VirtualPaymentTestDatabase();
    private final ClubVirtualPayGateway gateway = mock(ClubVirtualPayGateway.class);
    private final ClubBusinessService business = new ClubBusinessService(db.jdbc, mock(ClubCatalogService.class));
    private final ClubAppleRefundService service = new ClubAppleRefundService(db.jdbc, gateway, business);
    private final JSONObject original = new JSONObject();
    private final String no = "VP00000001";

    @BeforeEach
    void setup()
    {
        db.jdbc.execute("alter table club_payment add column stock_reserved tinyint default 0");
        db.jdbc.execute("alter table club_order_coupon add column coupon_issue_id bigint");
        db.jdbc.execute("create table club_coupon_issue(id bigint primary key,reserved_count int)");
        db.payment(1, "created", 7);
        db.jdbc.update("update club_payment set mock_transaction_no=null,paid_at=null,stock_reserved=1");
        db.jdbc.update("insert into club_order(id,order_no,user_id,provider_user_id,provider_type,total_amount,status,quantity,sku_id,product_id) values(1,'O1',7,9,'player',99,'unpaid',1,11,12)");
        db.jdbc.update("insert into club_product_sku values(11,9)");
        db.jdbc.update("insert into club_product values(12,0)");
        db.jdbc.update("insert into club_coupon_issue values(13,1)");
        db.jdbc.update("insert into club_order_coupon values(1,'reserved',13)");
        original.put("order_type", 7);
        original.put("paid_fee", 9900);
        original.put("status", 8);
        original.put("left_fee", 0);
        original.put("wx_order_id", "wx-1");
        when(gateway.confirmedAppleRefund(no)).thenReturn(original);
    }

    private void reconcile()
    {
        db.transaction.execute(status -> { service.reconcile(no); return null; });
    }

    @Test
    void externallyRefundedUnbookedPaymentReleasesOnlyItsReservationsExactlyOnce()
    {
        reconcile();
        reconcile();
        assertEquals("refunded", db.jdbc.queryForObject("select status from club_order", String.class));
        assertEquals("refunded", db.jdbc.queryForObject("select status from club_payment", String.class));
        assertEquals("wx-1", db.jdbc.queryForObject("select mock_transaction_no from club_payment", String.class));
        assertEquals(0, db.jdbc.queryForObject("select stock_reserved from club_payment", Integer.class));
        assertNull(db.jdbc.queryForObject("select paid_at from club_payment", Object.class));
        assertEquals(10, db.jdbc.queryForObject("select stock from club_product_sku", Integer.class));
        assertEquals(0, db.jdbc.queryForObject("select sales from club_product", Integer.class));
        assertEquals("released", db.jdbc.queryForObject("select status from club_order_coupon", String.class));
        assertEquals(0, db.jdbc.queryForObject("select reserved_count from club_coupon_issue", Integer.class));
        assertEquals(0, db.jdbc.queryForObject("select count(*) from club_wallet", Integer.class));
        assertEquals(0, db.jdbc.queryForObject("select count(*) from club_order_settlement", Integer.class));
        assertEquals(0, db.jdbc.queryForObject("select count(*) from club_teen_daily_spend", Integer.class));
        assertEquals(1, db.jdbc.queryForObject("select count(*) from club_aftersale", Integer.class));
    }

    @Test
    void incompleteReservationRollsBackAllRefundEffects()
    {
        db.jdbc.update("update club_payment set stock_reserved=0");
        assertThrows(ServiceException.class, this::reconcile);
        assertEquals("created", db.jdbc.queryForObject("select status from club_payment", String.class));
        assertNull(db.jdbc.queryForObject("select refund_status from club_payment", String.class));
        assertEquals("unpaid", db.jdbc.queryForObject("select status from club_order", String.class));
        assertEquals(9, db.jdbc.queryForObject("select stock from club_product_sku", Integer.class));
        assertEquals(0, db.jdbc.queryForObject("select count(*) from club_aftersale", Integer.class));
    }

    @Test
    void mismatchedAmountEnvironmentAndKnownTransactionRemainRejected()
    {
        original.put("paid_fee", 1);
        assertThrows(ServiceException.class, this::reconcile);
        original.put("paid_fee", 9900);
        db.jdbc.update("update club_virtual_payment set environment=1");
        assertThrows(ServiceException.class, this::reconcile);
        db.jdbc.update("update club_virtual_payment set environment=0");
        db.jdbc.update("update club_payment set mock_transaction_no='other-transaction'");
        assertThrows(ServiceException.class, this::reconcile);
        assertEquals("created", db.jdbc.queryForObject("select status from club_payment", String.class));
        assertEquals(9, db.jdbc.queryForObject("select stock from club_product_sku", Integer.class));
    }

    @Test
    void workerRoutesRefundBeforeLocalPaymentPostingToAppleReconciliation()
    {
        when(gateway.enabled()).thenReturn(true);
        ClubAppService app = mock(ClubAppService.class);
        ClubAppleRefundService apple = mock(ClubAppleRefundService.class);
        Map<String,Object> result = new HashMap<>();
        result.put("tradeState", "REFUND");
        when(app.syncWechatPayment(7L, 1L)).thenReturn(result);
        new ClubVirtualPaymentJob(db.jdbc, gateway, app, mock(ClubAfterSaleService.class), apple).reconcile();
        verify(apple).reconcile(no);
        verify(gateway, never()).confirmDelivery(anyString());
    }
}
