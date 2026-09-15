package com.ruoyi.club.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期完成服务方已经标记完成、但用户超时未确认的订单。 */
@Component
public class ClubOrderCompletionJob
{
    private static final Logger log = LoggerFactory.getLogger(ClubOrderCompletionJob.class);
    private final ClubBusinessService business;
    private final ClubAppService app;
    private final ClubAfterSaleService afterSale;

    public ClubOrderCompletionJob(ClubBusinessService business, ClubAppService app, ClubAfterSaleService afterSale)
    {
        this.business = business;
        this.app = app;
        this.afterSale = afterSale;
    }

    @Scheduled(fixedDelay = 300000L, initialDelay = 60000L)
    public void completeReadyOrders()
    {
        for (Long orderId : business.readyForAutoComplete())
        {
            try { business.autoCompleteOrder(orderId); }
            catch (Exception e) { log.error("自动完成俱乐部订单失败，orderId={}", orderId, e); }
        }
    }

    /** 服务端每分钟关闭超过30分钟仍未支付的订单，并释放占用的优惠券。 */
    @Scheduled(fixedDelay = 60000L, initialDelay = 30000L)
    public void expireUnpaidOrders()
    {
        try { app.expireUnpaidOrders(); }
        catch (Exception e) { log.error("自动关闭超时待支付订单失败", e); }
    }

    /** 售后保护期结束后把服务方冻结收益释放到可提现余额。 */
    @Scheduled(fixedDelay = 60000L, initialDelay = 45000L)
    public void releaseProtectedSettlements()
    {
        for (Long settlementId : business.readyProtectedSettlements())
        {
            try { business.releaseProtectedSettlement(settlementId); }
            catch (Exception e) { log.error("释放服务方保护期收益失败，settlementId={}", settlementId, e); }
        }
    }

    /** 服务方超过配置时限仍未处理售后时，幂等转入平台审核。 */
    @Scheduled(fixedDelay = 60000L, initialDelay = 50000L)
    public void escalateTimedOutAfterSales()
    {
        for (Long aftersaleId : afterSale.readyForPlatformReview())
        {
            try { afterSale.escalateProviderTimeout(aftersaleId); }
            catch (Exception e) { log.error("售后超时转平台审核失败，aftersaleId={}", aftersaleId, e); }
        }
    }
}
