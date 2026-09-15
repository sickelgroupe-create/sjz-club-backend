package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;

/** 青少年模式服务端统一策略，前端隐藏入口不能替代这里的校验。 */
@Service
public class ClubTeenPolicyService
{
    private final JdbcTemplate jdbc;

    public ClubTeenPolicyService(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    public void assertCanOrder(Long userId, BigDecimal amount)
    {
        if (!enabled(userId)) return;
        assertAllowedTime();
        if (enabledConfig("teen_block_order", true)) throw new ServiceException("青少年模式已开启，当前不允许下单");
        assertSpendLimit(userId, amount);
    }

    public void assertCanPay(Long userId, BigDecimal amount, String method)
    {
        if (!enabled(userId)) return;
        assertAllowedTime();
        if ("balance".equals(method) && enabledConfig("teen_block_balance_payment", true))
            throw new ServiceException("青少年模式已开启，当前不允许余额支付");
        assertSpendLimit(userId, amount);
    }

    /** Caller holds the user's payment lock until the pending payment is committed.
     * Pending WeChat payments reserve capacity until their external trade is closed.
     * Locking reads also see reservations committed while this request waited. */
    public void assertPaymentCapacity(Long userId, Long orderId, BigDecimal amount, String method) {
        assertCanPay(userId,amount,method);
        if(!enabled(userId))return;
        BigDecimal daily=decimalConfig("teen_daily_spend_limit",BigDecimal.ZERO);
        if(daily.signum()<=0)return;
        BigDecimal occupied=BigDecimal.ZERO;
        for(java.util.Map<String,Object> row:jdbc.queryForList("select amount from club_payment where user_id=? and mode='wechat' and status='created' and stock_reserved=1 and order_id<>? for update",userId,orderId))
            occupied=occupied.add(new BigDecimal(String.valueOf(row.get("amount"))));
        for(java.util.Map<String,Object> row:jdbc.queryForList("select amount from club_payment where user_id=? and status='success' and paid_at>=curdate() for update",userId))
            occupied=occupied.add(new BigDecimal(String.valueOf(row.get("amount"))));
        if(occupied.add(amount).compareTo(daily)>0)throw new ServiceException("今日已付款及待付款金额将超过每日限额，请先取消其他待付款订单");
    }

    /** A verified external charge cannot be rolled back by a local policy denial. */
    public boolean recordConfirmedWechatSpend(Long userId, BigDecimal amount) {
        if(!enabled(userId))return false;
        BigDecimal daily=decimalConfig("teen_daily_spend_limit",BigDecimal.ZERO);
        if(daily.signum()<=0)return false;
        jdbc.update("insert ignore into club_teen_daily_spend(user_id,spend_date,used_amount) select ?,curdate(),coalesce(sum(total_amount),0) from club_order where user_id=? and paid_at>=curdate() and status in ('pending','accepted','serving','completed','refunding')",userId,userId);
        BigDecimal used=jdbc.queryForObject("select used_amount from club_teen_daily_spend where user_id=? and spend_date=curdate() for update",BigDecimal.class,userId);
        BigDecimal next=(used==null?BigDecimal.ZERO:used).add(amount);
        jdbc.update("update club_teen_daily_spend set used_amount=?,updated_at=now() where user_id=? and spend_date=curdate()",next,userId);
        return next.compareTo(daily)>0;
    }

    /** Reserves today's successful spend under a row lock; call only inside the payment transaction. */
    public void reserveSuccessfulSpend(Long userId, BigDecimal amount)
    {
        if (!enabled(userId)) return;
        BigDecimal daily = decimalConfig("teen_daily_spend_limit", BigDecimal.ZERO);
        if (daily.compareTo(BigDecimal.ZERO) <= 0) return;
        jdbc.update("insert ignore into club_teen_daily_spend(user_id,spend_date,used_amount) " +
                "select ?,curdate(),coalesce(sum(total_amount),0) from club_order where user_id=? and paid_at>=curdate() and status in ('pending','accepted','serving','completed')",
                userId, userId);
        BigDecimal used = jdbc.queryForObject("select used_amount from club_teen_daily_spend where user_id=? and spend_date=curdate() for update", BigDecimal.class, userId);
        BigDecimal next = (used == null ? BigDecimal.ZERO : used).add(amount);
        if (next.compareTo(daily) > 0) throw new ServiceException("今日消费将超过青少年模式每日限额");
        jdbc.update("update club_teen_daily_spend set used_amount=?,updated_at=now() where user_id=? and spend_date=curdate()", next, userId);
    }

    public void assertCanRecharge(Long userId)
    {
        if (!enabled(userId)) return;
        assertAllowedTime();
        if (enabledConfig("teen_block_recharge", true)) throw new ServiceException("青少年模式已开启，当前不允许充值");
    }

    public void assertCanWithdraw(Long userId)
    {
        if (!enabled(userId)) return;
        assertAllowedTime();
        if (enabledConfig("teen_block_withdrawal", true)) throw new ServiceException("青少年模式已开启，当前不允许提现");
    }

    public boolean isContentAllowed(Long userId, String contentType)
    {
        if (userId == null || !enabled(userId)) return true;
        if (!isWithinAllowedTime()) return false;
        String configured = config("teen_content_scope", "product,news,announcement,promotion,document,ranking");
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .anyMatch(value -> value.equals("*") || value.equalsIgnoreCase(contentType));
    }

    public void assertContentAllowed(Long userId, String contentType)
    {
        if (userId == null || !enabled(userId)) return;
        assertAllowedTime();
        if (!isContentAllowed(userId, contentType))
            throw new ServiceException("青少年模式不允许查看该内容");
    }

    private void assertSpendLimit(Long userId, BigDecimal amount)
    {
        BigDecimal single = decimalConfig("teen_single_spend_limit", BigDecimal.ZERO);
        if (single.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(single) > 0)
            throw new ServiceException("本次消费超过青少年模式单次限额");
        BigDecimal daily = decimalConfig("teen_daily_spend_limit", BigDecimal.ZERO);
        if (daily.compareTo(BigDecimal.ZERO) > 0)
        {
            BigDecimal used = jdbc.queryForObject(
                    "select coalesce(sum(total_amount),0) from club_order where user_id=? and paid_at>=curdate() and status in ('pending','accepted','serving','completed')",
                    BigDecimal.class, userId);
            if ((used == null ? BigDecimal.ZERO : used).add(amount).compareTo(daily) > 0)
                throw new ServiceException("今日消费将超过青少年模式每日限额");
        }
    }

    private void assertAllowedTime()
    {
        if (!isWithinAllowedTime()) throw new ServiceException("当前时间不在青少年模式允许使用时段内");
    }

    private boolean isWithinAllowedTime()
    {
        LocalTime start = timeConfig("teen_allowed_start", LocalTime.of(6, 0));
        LocalTime end = timeConfig("teen_allowed_end", LocalTime.of(22, 0));
        LocalTime now = LocalTime.now();
        return start.equals(end) || (start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end));
    }

    private boolean enabled(Long userId)
    {
        Integer count = jdbc.queryForObject("select count(1) from club_teen_setting where user_id=? and enabled=1", Integer.class, userId);
        return count != null && count > 0;
    }

    private boolean enabledConfig(String key, boolean fallback)
    {
        return "1".equals(config(key, fallback ? "1" : "0"));
    }

    private BigDecimal decimalConfig(String key, BigDecimal fallback)
    {
        try { return new BigDecimal(config(key, fallback.toPlainString())); }
        catch (Exception e) { return fallback; }
    }

    private LocalTime timeConfig(String key, LocalTime fallback)
    {
        try { return LocalTime.parse(config(key, fallback.toString())); }
        catch (Exception e) { return fallback; }
    }

    private String config(String key, String fallback)
    {
        List<String> values = jdbc.query("select config_value from club_business_config where config_key=?", (rs, n) -> rs.getString(1), key);
        return values.isEmpty() ? fallback : values.get(0);
    }
}
