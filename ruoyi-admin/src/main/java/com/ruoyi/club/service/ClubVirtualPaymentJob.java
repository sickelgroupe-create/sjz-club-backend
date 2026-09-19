package com.ruoyi.club.service;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles without relying on the buyer keeping the payment page open. */
@Component
public class ClubVirtualPaymentJob
{
    private static final Logger LOG=LoggerFactory.getLogger(ClubVirtualPaymentJob.class);
    private final JdbcTemplate jdbc;
    private final ClubVirtualPayGateway gateway;
    private final ClubAppService app;
    private final ClubAfterSaleService afterSale;
    private final ClubAppleRefundService appleRefunds;
    public ClubVirtualPaymentJob(JdbcTemplate jdbc, ClubVirtualPayGateway gateway, ClubAppService app, ClubAfterSaleService afterSale, ClubAppleRefundService appleRefunds)
    { this.jdbc=jdbc;this.gateway=gateway;this.app=app;this.afterSale=afterSale;this.appleRefunds=appleRefunds; }

    @Scheduled(fixedDelay=30000L,initialDelay=60000L)
    public void reconcile()
    {
        if(!gateway.enabled())return;
        for(Map<String,Object> row:jdbc.queryForList("select p.payment_no,p.user_id,p.order_id,p.status from club_virtual_payment v join club_payment p on p.payment_no=v.payment_no where v.environment=0 and (p.status='created' or (p.status='success' and v.delivery_confirmed=0)) order by v.payment_checked_at,v.payment_no limit 100"))
        {
            String no=row.get("payment_no").toString();
            try {
                // Persist the attempt BEFORE external work, even when that work fails.
                // Old failures move behind unchecked orders, including after a restart.
                jdbc.update("update club_virtual_payment set payment_checked_at=current_timestamp(6),updated_at=updated_at where payment_no=?",no);
                if("created".equals(row.get("status")))app.syncWechatPayment(((Number)row.get("user_id")).longValue(),((Number)row.get("order_id")).longValue());
                gateway.confirmDelivery(no);
            } catch(Exception error) {
                // Never log remote bodies or signing credentials.
                LOG.warn("Virtual payment reconciliation pending: {} ({})",no,error.getClass().getSimpleName());
            }
        }
        for(Map<String,Object> row:jdbc.queryForList("select p.payment_no,p.refund_no,p.refund_status,v.order_type from club_payment p join club_virtual_payment v on v.payment_no=p.payment_no where v.environment=0 and p.status='success' and ((p.refund_status='processing' and v.refund_no is not null) or p.refund_status='apple_pending' or v.order_type=7) order by v.refund_checked_at,v.payment_no limit 100"))
        {
            String no=row.get("payment_no").toString();
            try {
                jdbc.update("update club_virtual_payment set refund_checked_at=current_timestamp(6),updated_at=updated_at where payment_no=?",no);
                if("apple_pending".equals(row.get("refund_status")) || Integer.valueOf(7).equals(row.get("order_type"))) {
                    appleRefunds.reconcile(no);
                    continue;
                }
                com.wechat.pay.java.service.refund.model.RefundNotification confirmed=gateway.confirmedRefund(no,row.get("refund_no").toString());
                if(confirmed!=null)afterSale.completeWechatRefund(confirmed);
            } catch(Exception error) { LOG.warn("Virtual refund reconciliation pending: {} ({})",no,error.getClass().getSimpleName()); }
        }
    }
}
