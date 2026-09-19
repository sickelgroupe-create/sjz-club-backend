package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import com.wechat.pay.java.service.payments.model.TransactionPayer;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Adapts verified xpay results to the existing accounting boundary; never trusts client success. */
@Service
public class ClubVirtualPayGateway
{
    private final JdbcTemplate jdbc;
    private final ClubVirtualPayClient client;
    public ClubVirtualPayGateway(JdbcTemplate jdbc, ClubVirtualPayClient client) { this.jdbc=jdbc; this.client=client; }
    public boolean enabled() { return client.enabled(); }
    public static boolean owns(String no) { return no != null && no.startsWith("VP"); }

    public Map<String,Object> prepare(String no, Map<String,Object> order, String openid, String code)
    {
        int env=client.environment();
        // Sandbox probes are isolated; never let sandbox receipts fund live orders or provider wallets.
        if(env!=0)throw new ServiceException("沙箱支付不可用于正式业务订单");
        int amount=ClubWechatPayService.cents(new BigDecimal(order.get("total_amount").toString()));
        int quantity=Integer.parseInt(order.get("quantity").toString());
        if(quantity<=0 || amount<=0 || amount%quantity!=0)throw new ServiceException("优惠后金额无法按服务数量精确分配，请调整数量或优惠券");
        List<Map<String,Object>> goods=jdbc.queryForList("select goods_id,price_fen from club_virtual_goods where sku_id=? and environment=? and published=1",order.get("sku_id"),env);
        if(goods.size()!=1)throw new ServiceException("该服务的虚拟支付商品尚未发布，请联系客服");
        Map<String,Object> item=goods.get(0);
        List<Map<String,Object>> existing=jdbc.queryForList("select * from club_virtual_payment where payment_no=?",no);
        if(!existing.isEmpty()) {
            Map<String,Object> saved=existing.get(0);
            if(!openid.equals(saved.get("openid")) || amount!=Integer.parseInt(saved.get("expected_fen").toString()) || env!=Integer.parseInt(saved.get("environment").toString())
                    || !item.get("goods_id").equals(saved.get("goods_id")) || quantity!=Integer.parseInt(saved.get("quantity").toString())
                    || Integer.parseInt(item.get("price_fen").toString())!=Integer.parseInt(saved.get("unit_price_fen").toString()))
                throw new ServiceException("支付记录与当前订单不一致");
        }
        Map<String,Object> params=client.prepareGoods(openid,code,no,item.get("goods_id").toString(),Integer.parseInt(item.get("price_fen").toString()),quantity,amount/quantity);
        if(existing.isEmpty())jdbc.update("insert into club_virtual_payment(payment_no,environment,openid,goods_id,expected_fen,unit_price_fen,quantity) values(?,?,?,?,?,?,?)",no,env,openid,item.get("goods_id"),amount,item.get("price_fen"),quantity);
        return params;
    }

    public JSONObject remote(String no)
    {
        Map<String,Object> saved=record(no);
        JSONObject result=client.query(saved.get("openid").toString(),no,Integer.parseInt(saved.get("environment").toString()));
        // A query can return an unfinished order with no channel or amounts. Never coerce missing values to success.
        jdbc.update("update club_virtual_payment set remote_status=?,order_type=?,remote_order_id=? where payment_no=?",
                result.getInteger("status"),result.getInteger("order_type"),result.getString("wx_order_id"),no);
        return result;
    }

    public Transaction query(String no, String appId, String merchantId)
    {
        Map<String,Object> saved=record(no);
        JSONObject result=remote(no);
        int state=result.getIntValue("status");
        Transaction transaction=new Transaction();transaction.setOutTradeNo(no);
        transaction.setAppid(appId);transaction.setMchid(merchantId);
        transaction.setTradeState(Transaction.TradeStateEnum.NOTPAY);
        if(state==6)transaction.setTradeState(Transaction.TradeStateEnum.CLOSED);
        else if(state==2 || state==3 || state==4) {
            validatePaid(result,Integer.parseInt(saved.get("expected_fen").toString()));
            if(Integer.parseInt(saved.get("environment").toString())!=0)throw new ServiceException("禁止将沙箱付款计入正式资金");
            TransactionAmount amount=new TransactionAmount();amount.setTotal(result.getInteger("paid_fee"));amount.setCurrency("CNY");
            TransactionPayer payer=new TransactionPayer();payer.setOpenid(saved.get("openid").toString());
            transaction.setPayer(payer);transaction.setAmount(amount);transaction.setTransactionId(result.getString("wx_order_id"));
            transaction.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        } else if(state==5 || state==8) transaction.setTradeState(Transaction.TradeStateEnum.REFUND);
        return transaction;
    }

    static void validatePaid(JSONObject result,int expected)
    {
        Integer type=result.getInteger("order_type"), paid=result.getInteger("paid_fee");
        if(type==null || (type!=0 && type!=7) || paid==null || paid!=expected || expected<=0
                || result.getString("wx_order_id")==null || result.getString("wx_order_id").isEmpty())
            throw new ServiceException("虚拟支付金额、渠道或交易号不匹配，不能记账");
    }

    public Refund refund(String no,String businessRefundNo,BigDecimal money)
    {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new ServiceException("虚拟退款提交必须在审核事务提交之后执行");
        Map<String,Object> saved=record(no);
        String refundNo=refundNumber(businessRefundNo);
        if(!refundNo.equals(saved.get("refund_no")))throw new ServiceException("必须先保存虚拟退款意图");
        JSONObject response=client.startRefund(saved.get("openid").toString(),no,refundNo,ClubWechatPayService.cents(money),Integer.parseInt(saved.get("environment").toString()));
        if(!refundNo.equals(response.getString("refund_order_id")) || !no.equals(response.getString("pay_order_id")))
            throw new ServiceException("退款受理信息不匹配，请查询后再处理");
        jdbc.update("update club_virtual_payment set refund_no=? where payment_no=?",refundNo,no);
        Refund refund=new Refund();refund.setOutRefundNo(businessRefundNo);refund.setOutTradeNo(no);
        refund.setRefundId(response.getString("refund_wx_order_id"));refund.setStatus(Status.PROCESSING);
        return refund;
    }

    static String refundNumber(String businessRefundNo)
    {
        String no="VR"+businessRefundNo.replaceAll("[^A-Za-z0-9]","");
        while(no.length()<8)no="VR0"+no;
        if(no.length()>32)throw new ServiceException("退款单号过长");
        return no;
    }

    /** Only a committed, approved intent can initiate a remote refund. A timeout
     * leaves that intent pending; a later run queries the same refund identity. */
    public RefundNotification submitPendingRefund(String no, String businessRefundNo)
    {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new ServiceException("虚拟退款提交必须在审核事务提交之后执行");
        List<Map<String,Object>> intents=jdbc.queryForList(
                "select p.amount from club_payment p join club_aftersale a on a.order_id=p.order_id and a.refund_idempotency_key=p.refund_no " +
                "where p.payment_no=? and p.refund_no=? and p.mode='wechat' and p.status='success' and p.refund_status='pending' and a.status='approved'", no,businessRefundNo);
        if(intents.size()!=1)return null;
        Map<String,Object> saved=record(no);
        if(!Integer.valueOf(0).equals(((Number)saved.get("environment")).intValue())
                || !refundNumber(businessRefundNo).equals(saved.get("refund_no")))
            throw new ServiceException("已保存的退款意图与支付记录不一致");
        int expected=ClubWechatPayService.cents(new BigDecimal(intents.get(0).get("amount").toString()));
        if(expected!=((Number)saved.get("expected_fen")).intValue())throw new ServiceException("退款意图金额不一致");
        JSONObject original=remote(no);
        validatePaid(original,expected);
        if(!Integer.valueOf(0).equals(original.getInteger("order_type")))throw new ServiceException("该渠道不可由平台提交退款");
        Integer remaining=original.getInteger("left_fee");
        if(Integer.valueOf(0).equals(remaining))return confirmedRefund(no,businessRefundNo);
        if(!Integer.valueOf(expected).equals(remaining))throw new ServiceException("退款余额异常，请人工核对");
        Refund accepted=refund(no,businessRefundNo,BigDecimal.valueOf(expected,2));
        // Acceptance is never a completed refund. A concurrent callback may have
        // already completed it, so do not overwrite that terminal state.
        jdbc.update("update club_payment set refund_status='processing',wechat_refund_id=coalesce(wechat_refund_id,?) " +
                "where payment_no=? and refund_no=? and status='success' and refund_status='pending'",
                accepted.getRefundId(),no,businessRefundNo);
        return null;
    }

    public void confirmDelivery(String no)
    {
        Map<String,Object> saved=record(no);
        if(Integer.parseInt(saved.get("delivery_confirmed").toString())==1)return;
        Integer count=jdbc.queryForObject("select count(*) from club_payment where payment_no=? and status='success'",Integer.class,no);
        if(count==null || count!=1)throw new ServiceException("本地未确认付款，不可通知发货");
        JSONObject current=remote(no);
        validatePaid(current,Integer.parseInt(saved.get("expected_fen").toString()));
        int state=current.getIntValue("status");
        if(state!=2 && state!=3 && state!=4)throw new ServiceException("当前虚拟支付状态不可确认发货");
        if(state!=4)client.confirmDelivery(no,Integer.parseInt(saved.get("environment").toString()));
        // Only the follow-up query, not acceptance of the notification request, proves delivery.
        if(remote(no).getIntValue("status")==4)jdbc.update("update club_virtual_payment set delivery_confirmed=1 where payment_no=?",no);
    }

    public RefundNotification confirmedRefund(String no, String businessRefundNo)
    {
        Map<String,Object> saved=record(no);
        if(saved.get("refund_no")==null)return null;
        return confirmedRefund(no,businessRefundNo,saved.get("refund_no").toString());
    }

    /** Verify an authenticated callback's candidate before persisting its refund ID. */
    public RefundNotification confirmedRefund(String no, String businessRefundNo, String refundNo)
    {
        if(refundNo==null || !refundNo.matches("[A-Za-z0-9_-]{8,32}"))throw new ServiceException("退款单号无效");
        Map<String,Object> saved=record(no);
        int env=Integer.parseInt(saved.get("environment").toString());
        if(env!=0)throw new ServiceException("沙箱退款不可用于正式资金冲正");
        JSONObject refund=client.query(saved.get("openid").toString(),refundNo,env);
        Integer state=refund.getInteger("status");
        if(state==null || (state!=5 && state!=8))return null;
        int expected=Integer.parseInt(saved.get("expected_fen").toString());
        if((!Integer.valueOf(1).equals(refund.getInteger("order_type")) && !Integer.valueOf(8).equals(refund.getInteger("order_type"))) || !Integer.valueOf(expected).equals(refund.getInteger("refund_fee"))
                || refund.getString("wx_order_id")==null || refund.getString("wx_order_id").isEmpty())
            throw new ServiceException("虚拟退款渠道、金额或流水不匹配");
        JSONObject original=remote(no);
        validatePaid(original,expected);
        if((original.getIntValue("order_type")==7)!=(refund.getIntValue("order_type")==8))throw new ServiceException("原支付与退款渠道不匹配");
        if(!Integer.valueOf(0).equals(original.getInteger("left_fee")))throw new ServiceException("原支付单尚未确认全额退回");
        RefundNotification result=new RefundNotification();
        result.setOutTradeNo(no);result.setOutRefundNo(businessRefundNo);result.setTransactionId(original.getString("wx_order_id"));
        result.setRefundId(refund.getString("wx_order_id"));result.setRefundStatus(Status.SUCCESS);
        Amount amount=new Amount();amount.setRefund((long)expected);amount.setTotal((long)expected);amount.setCurrency("CNY");result.setAmount(amount);
        return result;
    }

    /** Original-order evidence also recovers Apple refunds whose notification was lost.
     * query_order documents status 5/8 as refunded and left_fee as remaining after refunds.
     * No refund transaction ID is invented when only the original order is available.
     */
    public JSONObject confirmedAppleRefund(String no)
    {
        Map<String,Object> saved=record(no);
        if(Integer.parseInt(saved.get("environment").toString())!=0)
            throw new ServiceException("沙箱退款不可用于正式资金冲正");
        JSONObject original=remote(no);
        if(!Integer.valueOf(7).equals(original.getInteger("order_type")))return null;
        Integer state=original.getInteger("status");
        if(!Integer.valueOf(5).equals(state) && !Integer.valueOf(8).equals(state))return null;
        validatePaid(original,Integer.parseInt(saved.get("expected_fen").toString()));
        if(!Integer.valueOf(0).equals(original.getInteger("left_fee")))
            throw new ServiceException("苹果退款尚未确认全额退回，请人工核对");
        return original;
    }

    private Map<String,Object> record(String no)
    {
        List<Map<String,Object>> rows=jdbc.queryForList("select * from club_virtual_payment where payment_no=?",no);
        if(rows.size()!=1)throw new ServiceException("虚拟支付记录不存在");
        return rows.get(0);
    }
}
