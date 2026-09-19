package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Books a refund already completed by Apple, never requests or approves a new refund. */
@Service
public class ClubAppleRefundService
{
    private final JdbcTemplate jdbc;
    private final ClubVirtualPayGateway gateway;
    private final ClubBusinessService business;

    public ClubAppleRefundService(JdbcTemplate jdbc, ClubVirtualPayGateway gateway, ClubBusinessService business)
    { this.jdbc=jdbc; this.gateway=gateway; this.business=business; }

    @Transactional
    public void reconcile(String paymentNo)
    { complete(paymentNo,null,null,null,null); }

    @Transactional
    public void confirmNotification(String paymentNo, String refundNo, String refundId, Long refundFee, String transactionId)
    {
        if(refundNo==null || refundId==null || refundFee==null || transactionId==null)
            throw new ServiceException("苹果退款通知字段缺失");
        complete(paymentNo,refundNo,refundId,refundFee,transactionId);
    }

    private void complete(String no, String remoteRefundNo, String refundId, Long refundFee, String transactionId)
    {
        JSONObject original=gateway.confirmedAppleRefund(no);
        if(original==null) {
            if(remoteRefundNo!=null)throw new ServiceException("苹果退款查询尚未确认成功");
            return;
        }
        if(remoteRefundNo!=null) {
            RefundNotification verified=gateway.confirmedRefund(no,no,remoteRefundNo);
            if(verified==null || !refundId.equals(verified.getRefundId())
                    || !refundFee.equals(verified.getAmount().getRefund())
                    || !transactionId.equals(verified.getTransactionId())
                    || !transactionId.equals(original.getString("wx_order_id")))
                throw new ServiceException("苹果退款通知与查单结果不匹配");
        }
        // Lock the order before local money changes. Both polling and duplicate callbacks
        // use this boundary, and all local effects commit (or roll back) together.
        List<Map<String,Object>> candidates=jdbc.queryForList("select order_id from club_payment where payment_no=?",no);
        if(candidates.size()!=1)throw new ServiceException("苹果原支付记录不存在");
        Object orderId=candidates.get(0).get("order_id");
        Map<String,Object> order=jdbc.queryForMap("select * from club_order where id=? for update",orderId);
        List<Map<String,Object>> payments=jdbc.queryForList(
                "select p.*,v.environment,v.expected_fen from club_payment p join club_virtual_payment v on v.payment_no=p.payment_no where p.payment_no=? for update",no);
        if(payments.size()!=1)throw new ServiceException("苹果原支付记录不唯一");
        Map<String,Object> payment=payments.get(0);
        boolean unbooked="created".equals(payment.get("status"));
        BigDecimal amount=new BigDecimal(payment.get("amount").toString());
        int cents=ClubWechatPayService.cents(amount);
        if(!ClubVirtualPayGateway.owns(no) || !"wechat".equals(payment.get("mode"))
                || !Integer.valueOf(0).equals(((Number)payment.get("environment")).intValue())
                || cents!=((Number)payment.get("expected_fen")).intValue()
                || !Integer.valueOf(cents).equals(original.getInteger("paid_fee"))
                || ((!unbooked || payment.get("mock_transaction_no")!=null)
                    && !original.getString("wx_order_id").equals(payment.get("mock_transaction_no")))
                || !orderId.equals(payment.get("order_id")) || !order.get("user_id").equals(payment.get("user_id"))
                || amount.compareTo(new BigDecimal(order.get("total_amount").toString()))!=0)
            throw new ServiceException("苹果退款与本地原支付流水或金额不匹配");
        if(!Arrays.asList("created","success","refunded").contains(payment.get("status")))
            throw new ServiceException("苹果原支付尚未入账，请人工核对");
        if(remoteRefundNo!=null) {
            if(jdbc.update("update club_virtual_payment set refund_no=? where payment_no=? and (refund_no is null or refund_no=?)",remoteRefundNo,no,remoteRefundNo)!=1)
                throw new ServiceException("苹果退款单号冲突，请人工核对");
            if(jdbc.update("update club_payment set wechat_refund_id=? where id=? and (wechat_refund_id is null or wechat_refund_id=?)",refundId,payment.get("id"),refundId)!=1)
                throw new ServiceException("苹果退款流水冲突，请人工核对");
        }
        if("refunded".equals(payment.get("status")))return;
        if(unbooked ? !"unpaid".equals(order.get("status"))
                : !Arrays.asList("pending","accepted","serving","completed","refunding").contains(order.get("status")))
            throw new ServiceException("苹果退款对应订单状态异常，请人工核对");

        String key=payment.get("refund_no")==null ? "AIR-"+payment.get("id") : payment.get("refund_no").toString();
        String note="Apple已完成全额退款，经微信服务端查单确认；这是外部退款结果入账，不是平台审核批准";
        List<Map<String,Object>> cases=jdbc.queryForList("select * from club_aftersale where order_id=? for update",orderId);
        if(cases.isEmpty()) {
            jdbc.update("insert into club_aftersale(aftersale_no,order_id,user_id,provider_user_id,status,reason,description,evidence_json,refund_amount,request_id,refund_idempotency_key) values(?,?,?,?,'external_confirmed','Apple外部退款',?,'[]',?,?,?)",
                    "ASI-"+payment.get("id"),orderId,payment.get("user_id"),order.get("provider_user_id"),note,amount,"apple-refund:"+no,key);
            cases=jdbc.queryForList("select * from club_aftersale where order_id=? for update",orderId);
        }
        if(cases.size()!=1)throw new ServiceException("苹果退款售后记录不唯一");
        Map<String,Object> aftersale=cases.get(0);
        Long aftersaleId=((Number)aftersale.get("id")).longValue();
        jdbc.update("update club_payment set refund_no=?,refund_status='success' where id=? and status in ('created','success')",key,payment.get("id"));
        jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) values(?,?,?,'apple_refund_confirm','success',?,?)",
                payment.get("id"),orderId,payment.get("user_id"),"APPLE-"+no,
                "query_order: order_type=7,status="+original.getInteger("status")+",left_fee=0,paid_fee="+cents+",wx_order_id="+original.getString("wx_order_id"));
        if(unbooked)business.executeUnbookedAppleRefund(((Number)orderId).longValue(),((Number)payment.get("id")).longValue(),amount,original.getString("wx_order_id"),note);
        else business.executeExternalAppleRefund(((Number)orderId).longValue(),aftersaleId,amount,note);
        // Preserve any prior reviewer and review note; an Apple decision is not an admin approval.
        jdbc.update("update club_aftersale set status='refunded',refund_amount=?,refund_idempotency_key=?,refunded_at=now() where id=?",amount,key,aftersaleId);
        jdbc.update("insert into club_aftersale_log(aftersale_id,from_status,to_status,operator_type,note) values(?,?,'refunded','apple',?)",
                aftersaleId,aftersale.get("status"),note);
    }
}
