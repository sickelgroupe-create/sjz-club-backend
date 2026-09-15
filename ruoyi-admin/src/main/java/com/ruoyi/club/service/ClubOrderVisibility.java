package com.ruoyi.club.service;

/** Operational views exclude proven non-monetary legacy orders without deleting accounting evidence. */
public final class ClubOrderVisibility {
    private ClubOrderVisibility() { }
    public static String operational(String alias) {
        if (!alias.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid internal alias");
        return "(not exists(select 1 from club_payment legacy_payment where legacy_payment.order_id=" + alias + ".id and legacy_payment.mode in ('mock','mock_wechat')) " +
                "or exists(select 1 from club_payment monetary_payment where monetary_payment.order_id=" + alias + ".id and monetary_payment.mode in ('wechat','balance')))";
    }
}
