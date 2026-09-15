package com.ruoyi.club.service;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClubWalletPolicyTest {
    private BigDecimal available(int balance,int income,int withdrawn,int frozen){Map<String,Object> w=new HashMap<>();w.put("balance",balance);w.put("totalIncome",income);w.put("totalWithdrawn",withdrawn);w.put("frozen",frozen);return ClubWalletPolicy.withdrawable(w);}
    @Test void rechargeIsNotServiceEarnings(){assertEquals(new BigDecimal("0"),available(100,0,0,0));}
    @Test void subtractsPreviousWithdrawalsAndFrozenFunds(){assertEquals(new BigDecimal("50"),available(1000,100,30,20));}
    @Test void cappedByActualAvailableFunds(){assertEquals(new BigDecimal("12"),available(12,100,0,0));}
    @Test void neverNegative(){assertEquals(new BigDecimal("0"),available(12,0,30,10));}
}
