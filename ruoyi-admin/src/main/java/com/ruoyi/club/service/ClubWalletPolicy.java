package com.ruoyi.club.service;
import java.math.BigDecimal;
import java.util.Map;
/** Withdrawals may not exceed either available funds or unfrozen net service earnings. */
final class ClubWalletPolicy {
    static BigDecimal withdrawable(Map<String,Object> wallet) {
        return amount(wallet.get("balance")).min(amount(wallet.get("totalIncome"))
            .subtract(amount(wallet.get("totalWithdrawn"))).subtract(amount(wallet.get("frozen"))))
            .max(BigDecimal.ZERO);
    }
    private static BigDecimal amount(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
}
