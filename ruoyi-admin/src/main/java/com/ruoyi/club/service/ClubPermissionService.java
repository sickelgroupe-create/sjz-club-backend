package com.ruoyi.club.service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.SecurityUtils;

/** Club后台动态实体权限，只接受细粒度授权。 */
@Service("clubPermission")
public class ClubPermissionService
{
    private static final Map<String, String> DOMAINS = new HashMap<>();
    static
    {
        DOMAINS.put("users", "user"); DOMAINS.put("shops", "shop");
        DOMAINS.put("products", "product"); DOMAINS.put("skus", "product");
        DOMAINS.put("players", "player"); DOMAINS.put("playerServices", "player");
        DOMAINS.put("orders", "order"); DOMAINS.put("orderLogs", "order");
        DOMAINS.put("payments", "order"); DOMAINS.put("paymentAudits", "order");
        DOMAINS.put("aftersales", "aftersale"); DOMAINS.put("receivables", "receivable");
        DOMAINS.put("applications", "application"); DOMAINS.put("identities", "identity");
        DOMAINS.put("withdrawals", "withdrawal"); DOMAINS.put("wallets", "wallet");
        DOMAINS.put("walletRecords", "wallet"); DOMAINS.put("settlements", "wallet");
        DOMAINS.put("content", "content"); DOMAINS.put("categories", "content"); DOMAINS.put("homeEntries", "content");
        DOMAINS.put("messages", "content"); DOMAINS.put("reviews", "content");
        DOMAINS.put("rechargeRules","coupon"); DOMAINS.put("rechargeRewards","coupon");
        DOMAINS.put("coupons", "coupon"); DOMAINS.put("couponIssues", "coupon");
        DOMAINS.put("businessConfig", "finance-config"); DOMAINS.put("rechargeTiers", "finance-config");
        DOMAINS.put("customerConfig", "customer-service"); DOMAINS.put("teenSettings", "teen-config");
        DOMAINS.put("rechargeOrders", "finance"); DOMAINS.put("follows", "shop");
        DOMAINS.put("favorites", "product"); DOMAINS.put("adminAudits", "audit");
    }

    public boolean canDashboard() { return has("club:dashboard:list"); }
    public boolean canList(String type) { return has(permission(type, "list")); }
    public boolean canDetail(String type)
    {
        if ("orders".equals(type)) return has("club:order:detail");
        if ("adminAudits".equals(type)) return has("club:audit:detail");
        if ("identities".equals(type)) return has("club:identity:detail");
        if ("applications".equals(type)) return has("club:application:list") && has("club:identity:detail");
        return canList(type);
    }
    public boolean canSave(String type)
    {
        if ("aftersales".equals(type)) return has("club:aftersale:review");
        if ("applications".equals(type)) return has("club:application:review") && has("club:identity:review");
        if ("identities".equals(type)) return has("club:identity:review");
        if ("withdrawals".equals(type)) return has("club:withdrawal:review");
        if ("receivables".equals(type)) return has("club:receivable:writeoff");
        if ("orders".equals(type)) return has("club:order:operate");
        return has(permission(type, "edit"));
    }
    public boolean canDelete(String type) { return has(permission(type, "disable")); }
    public String permission(String type, String action)
    {
        String domain = DOMAINS.get(type);
        return domain == null ? "club:denied" : "club:" + domain + ":" + action;
    }
    private boolean has(String permission)
    {
        return SecurityUtils.hasPermi(permission);
    }
}
