package com.ruoyi.club.service;

import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.wechat.pay.java.service.refund.model.RefundNotification;

/** Invoked only after message authentication and AppID-bound decryption. */
@Service
public class ClubWechatMessageService {
    private final JdbcTemplate jdbc;private final ClubAppService app;private final ClubVirtualPayGateway gateway;private final ClubAfterSaleService afterSale;
    private final ClubAppleRefundService appleRefunds;
    public ClubWechatMessageService(JdbcTemplate jdbc,ClubAppService app,ClubVirtualPayGateway gateway,ClubAfterSaleService afterSale,ClubAppleRefundService appleRefunds){this.jdbc=jdbc;this.app=app;this.gateway=gateway;this.afterSale=afterSale;this.appleRefunds=appleRefunds;}
    @Transactional
    public JSONObject handle(JSONObject event) {
        String kind=event.getString("Event");
        if("debug_demo".equals(kind))return null;
        if(!gateway.enabled())throw new IllegalStateException("Virtual payment disabled");
        if("xpay_goods_deliver_notify".equals(kind)){
            Map<String,Object> payment=payment(event.getString("OutTradeNo"),event);
            app.syncWechatPayment(((Number)payment.get("user_id")).longValue(),((Number)payment.get("order_id")).longValue());
            Integer paid=jdbc.queryForObject("select count(*) from club_payment where payment_no=? and status='success'",Integer.class,event.getString("OutTradeNo"));
            if(paid==null || paid!=1)throw new IllegalStateException("Payment not confirmed");
            return null;
        }
        if("xpay_refund_notify".equals(kind)){
            String no=event.getString("MchOrderId");Map<String,Object> payment=payment(no,event);
            if(!Integer.valueOf(0).equals(event.getInteger("RetCode")))throw new IllegalStateException("Refund not successful");
            String refund=event.getString("MchRefundId");
            if(refund==null || !refund.matches("[A-Za-z0-9_-]{8,32}"))throw new IllegalStateException("Invalid refund identity");
            Map<String,Object> remote=gateway.remote(no);
            if(event.getString("WxOrderId")==null || !event.getString("WxOrderId").equals(remote.get("wx_order_id")))throw new IllegalStateException("Payment identity mismatch");
            if(Integer.valueOf(7).equals(remote.get("order_type"))) {
                appleRefunds.confirmNotification(no,refund,event.getString("WxRefundId"),event.getLong("RefundFee"),event.getString("WxOrderId"));
                return null;
            }
            if(payment.get("refund_no")==null)throw new IllegalStateException("Refund requires reconciliation");
            RefundNotification confirmed=gateway.confirmedRefund(no,payment.get("refund_no").toString(),refund);
            if(confirmed==null || !confirmed.getRefundId().equals(event.getString("WxRefundId")) || !confirmed.getAmount().getRefund().equals(event.getLong("RefundFee")))throw new IllegalStateException("Refund query not confirmed");
            if(jdbc.update("update club_virtual_payment set refund_no=? where payment_no=? and (refund_no is null or refund_no=?)",refund,no,refund)!=1)throw new IllegalStateException("Refund identity conflict");
            afterSale.completeWechatRefund(confirmed);return null;
        }
        throw new IllegalStateException("Unsupported payment event");
    }
    private Map<String,Object> payment(String no,JSONObject event){
        if(!ClubVirtualPayGateway.owns(no) || (event.containsKey("Env") && !Integer.valueOf(0).equals(event.getInteger("Env")))
                || ("xpay_goods_deliver_notify".equals(event.getString("Event")) && !event.containsKey("Env")))throw new IllegalArgumentException("Invalid payment scope");
        List<Map<String,Object>> rows=jdbc.queryForList("select p.*,v.openid from club_payment p join club_virtual_payment v on v.payment_no=p.payment_no where p.payment_no=? and v.environment=0 for update",no);
        if(rows.size()!=1 || !rows.get(0).get("openid").equals(event.getString("OpenId")))throw new IllegalArgumentException("Invalid payment identity");return rows.get(0);
    }
}
