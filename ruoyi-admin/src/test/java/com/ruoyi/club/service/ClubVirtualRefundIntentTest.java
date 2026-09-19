package com.ruoyi.club.service;

import java.util.HashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Entirely local fixtures. No Spring application/payment credentials are loaded. */
class ClubVirtualRefundIntentTest
{
    private final VirtualPaymentTestDatabase db = new VirtualPaymentTestDatabase();
    private final ClubVirtualPayClient client = mock(ClubVirtualPayClient.class);
    private final ClubVirtualPayGateway gateway = new ClubVirtualPayGateway(db.jdbc, client);
    private final ClubWechatPayService wechat = mock(ClubWechatPayService.class);
    private final ClubBusinessService business = new ClubBusinessService(db.jdbc, mock(ClubCatalogService.class));
    private final ClubAfterSaleService afterSale = new ClubAfterSaleService(db.jdbc, business, wechat);
    private final JSONObject original = new JSONObject();
    private final String no = "VP00000001";
    private final String businessRefundNo = "AFR-1";
    private final String remoteRefundNo = ClubVirtualPayGateway.refundNumber(businessRefundNo);

    @BeforeEach
    void setup()
    {
        db.jdbc.execute("alter table club_order add column product_name varchar(64) default 'service'");
        db.jdbc.execute("alter table club_order add column sku_name varchar(64) default 'sku'");
        db.jdbc.execute("alter table club_aftersale add column reviewed_at timestamp");
        db.jdbc.execute("alter table club_aftersale add column review_request_id varchar(96)");
        db.jdbc.execute("alter table club_aftersale_log add column created_at timestamp default current_timestamp");
        db.jdbc.execute("alter table club_provider_receivable add column recovered_amount decimal(12,2) default 0");
        db.jdbc.execute("alter table club_provider_receivable add column created_at timestamp default current_timestamp");
        db.payment(1, "success", 0);
        db.jdbc.update("update club_virtual_payment set delivery_confirmed=1");
        db.jdbc.update("insert into club_order(id,order_no,user_id,provider_type,total_amount,status,quantity,sku_id,product_id) values(1,'O1',7,'platform',99,'refunding',1,11,12)");
        db.jdbc.update("insert into club_product_sku values(11,10)");
        db.jdbc.update("insert into club_product values(12,1)");
        db.jdbc.update("insert into club_aftersale(id,aftersale_no,order_id,user_id,status,refund_amount,request_id) values(1,'AS1',1,7,'platform_reviewing',99,'apply-1')");
        original.put("order_type", 0);
        original.put("paid_fee", 9900);
        original.put("wx_order_id", "wx-1");
        original.put("status", 4);
        original.put("left_fee", 9900);
        when(client.enabled()).thenReturn(true);
        when(client.query("openid-7", no, 0)).thenReturn(original);
        JSONObject accepted = new JSONObject();
        accepted.put("refund_order_id", remoteRefundNo);
        accepted.put("pay_order_id", no);
        accepted.put("refund_wx_order_id", "wx-refund-1");
        when(client.startRefund("openid-7", no, remoteRefundNo, 9900, 0)).thenReturn(accepted);
    }

    private void approve()
    {
        Map<String,Object> input = new HashMap<>();
        input.put("action", "approve");
        input.put("requestId", "approve-1");
        input.put("note", "本地测试全额退款");
        db.transaction.execute(status -> afterSale.adminReview(1L, 1L, input));
    }

    private void remoteRefundCompleted()
    {
        original.put("status", 8);
        original.put("left_fee", 0);
        JSONObject refund = new JSONObject();
        refund.put("order_type", 1);
        refund.put("refund_fee", 9900);
        refund.put("wx_order_id", "wx-refund-1");
        refund.put("status", 8);
        when(client.query("openid-7", remoteRefundNo, 0)).thenReturn(refund);
    }

    @Test
    void approvalCommitsIntentAndAcceptanceDoesNotReverseMoney()
    {
        approve();
        assertEquals("pending", db.jdbc.queryForObject("select refund_status from club_payment", String.class));
        assertEquals(remoteRefundNo, db.jdbc.queryForObject("select refund_no from club_virtual_payment", String.class));
        verifyNoInteractions(client);
        verify(wechat, never()).refund(anyString(), anyString(), any(), anyString());
        assertNull(gateway.submitPendingRefund(no, businessRefundNo));
        assertEquals("processing", db.jdbc.queryForObject("select refund_status from club_payment", String.class));
        assertEquals("success", db.jdbc.queryForObject("select status from club_payment", String.class));
        assertEquals("refunding", db.jdbc.queryForObject("select status from club_order", String.class));
        assertEquals(10, db.jdbc.queryForObject("select stock from club_product_sku", Integer.class));
    }

    @Test
    void timeoutAfterRemoteSuccessRecoversByTheCommittedIdentity()
    {
        approve();
        when(client.startRefund("openid-7", no, remoteRefundNo, 9900, 0)).thenAnswer(call -> {
            remoteRefundCompleted();
            throw new ServiceException("simulated timeout after remote refund");
        });
        assertThrows(ServiceException.class, () -> gateway.submitPendingRefund(no, businessRefundNo));
        assertEquals("pending", db.jdbc.queryForObject("select refund_status from club_payment", String.class));
        RefundNotification confirmed = gateway.submitPendingRefund(no, businessRefundNo);
        assertNotNull(confirmed);
        db.transaction.execute(status -> { afterSale.completeWechatRefund(confirmed); return null; });
        assertEquals("refunded", db.jdbc.queryForObject("select status from club_payment", String.class));
        assertEquals("refunded", db.jdbc.queryForObject("select status from club_order", String.class));
        assertEquals(11, db.jdbc.queryForObject("select stock from club_product_sku", Integer.class));
        verify(client, times(1)).startRefund("openid-7", no, remoteRefundNo, 9900, 0);
        assertNull(gateway.submitPendingRefund(no, businessRefundNo));
        assertEquals(11, db.jdbc.queryForObject("select stock from club_product_sku", Integer.class));
        confirmed.setRefundStatus(com.wechat.pay.java.service.refund.model.Status.PROCESSING);
        db.transaction.execute(status -> { afterSale.completeWechatRefund(confirmed); return null; });
        assertEquals("success", db.jdbc.queryForObject("select refund_status from club_payment", String.class));
    }

    @Test
    void workerPicksUpPendingIntentAfterRestart()
    {
        approve();
        ClubVirtualPaymentJob job = new ClubVirtualPaymentJob(db.jdbc, gateway, mock(ClubAppService.class), afterSale,
                mock(ClubAppleRefundService.class));
        job.reconcile();
        verify(client).startRefund("openid-7", no, remoteRefundNo, 9900, 0);
        remoteRefundCompleted();
        new ClubVirtualPaymentJob(db.jdbc, gateway, mock(ClubAppService.class), afterSale,
                mock(ClubAppleRefundService.class)).reconcile();
        assertEquals("refunded", db.jdbc.queryForObject("select status from club_payment", String.class));
    }

    @Test
    void rolledBackApprovalCannotStartRemoteRefund()
    {
        db.transaction.execute(status -> { approve(); status.setRollbackOnly(); return null; });
        assertEquals("platform_reviewing", db.jdbc.queryForObject("select status from club_aftersale", String.class));
        assertNull(db.jdbc.queryForObject("select refund_no from club_payment", String.class));
        assertNull(gateway.submitPendingRefund(no, businessRefundNo));
        verifyNoInteractions(client);
    }

    @Test
    void remoteMutationCannotRunInsideTheApprovalTransaction()
    {
        approve();
        assertThrows(ServiceException.class, () -> db.transaction.execute(status -> gateway.submitPendingRefund(no, businessRefundNo)));
        verifyNoInteractions(client);
    }
}
