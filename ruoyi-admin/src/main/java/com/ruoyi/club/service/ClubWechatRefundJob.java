package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Native WeChat refunds use the same committed-intent/verified-result boundary. */
@Component
public class ClubWechatRefundJob
{
    private final JdbcTemplate jdbc;
    private final ClubWechatPayService wechat;
    private final ClubAfterSaleService afterSale;

    public ClubWechatRefundJob(JdbcTemplate jdbc,ClubWechatPayService wechat,ClubAfterSaleService afterSale)
    { this.jdbc=jdbc;this.wechat=wechat;this.afterSale=afterSale; }

    @Scheduled(fixedDelay=30000L,initialDelay=65000L)
    public void reconcile()
    {
        if(!wechat.enabled())return;
        // ABNORMAL is recoverable after merchant intervention, so keep querying
        // the same identity. Only a pending intent may submit a refund below.
        for(Map<String,Object> row:jdbc.queryForList("select p.* from club_payment p join club_aftersale a on a.order_id=p.order_id and a.refund_idempotency_key=p.refund_no " +
                "where p.mode='wechat' and p.payment_no not like 'VP%' and p.status='success' and p.refund_status in ('pending','processing','abnormal') and a.status='approved' " +
                "order by p.refund_checked_at,p.id limit 100"))
        {
            String no=row.get("payment_no").toString(),refundNo=row.get("refund_no").toString();
            try
            {
                jdbc.update("update club_payment set refund_checked_at=current_timestamp(6) where id=?",row.get("id"));
                Refund refund=wechat.queryRefund(refundNo);
                if(refund==null && "pending".equals(row.get("refund_status")))
                    refund=wechat.refund(no,refundNo,new BigDecimal(row.get("amount").toString()),"平台售后全额退款");
                if(refund==null)throw new ServiceException("已受理退款查单暂未返回结果");
                int expected=ClubWechatPayService.cents(new BigDecimal(row.get("amount").toString()));
                if(!no.equals(refund.getOutTradeNo()) || !refundNo.equals(refund.getOutRefundNo())
                        || !row.get("mock_transaction_no").equals(refund.getTransactionId())
                        || refund.getRefundId()==null || refund.getRefundId().isEmpty()
                        || refund.getAmount()==null || !Long.valueOf(expected).equals(refund.getAmount().getRefund())
                        || !Long.valueOf(expected).equals(refund.getAmount().getTotal()) || !"CNY".equals(refund.getAmount().getCurrency()))
                    throw new ServiceException("微信退款查询身份或金额不匹配");
                if(Status.SUCCESS.equals(refund.getStatus()))
                {
                    RefundNotification confirmed=new RefundNotification();
                    confirmed.setOutTradeNo(no);confirmed.setOutRefundNo(refundNo);
                    confirmed.setTransactionId(refund.getTransactionId());confirmed.setRefundId(refund.getRefundId());
                    confirmed.setRefundStatus(Status.SUCCESS);confirmed.setAmount(refund.getAmount());
                    afterSale.completeWechatRefund(confirmed);
                }
                else if(Status.PROCESSING.equals(refund.getStatus()))
                    jdbc.update("update club_payment set refund_status='processing',wechat_refund_id=coalesce(wechat_refund_id,?) where id=? and status='success' and refund_status in ('pending','processing')",refund.getRefundId(),row.get("id"));
                else throw new ServiceException("微信退款未完成，请在商户平台核对");
            }
            catch(RuntimeException failure)
            {
                org.slf4j.LoggerFactory.getLogger(ClubWechatRefundJob.class).warn("WeChat refund reconciliation pending: {} ({})",no,failure.getClass().getSimpleName());
            }
        }
    }
}
