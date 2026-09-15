package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.payments.model.Transaction;

class ClubRealRechargeTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ClubWechatPayService pay=mock(ClubWechatPayService.class);
    final ClubAppService service=new ClubAppService(jdbc,mock(ClubAuthService.class),mock(ClubBusinessService.class),
        mock(ClubCatalogService.class),mock(ClubTeenPolicyService.class),mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),pay);
    final Map<String,Object> row=new HashMap<>();
    Transaction transaction(){
        row.put("id",8L);row.put("user_id",39L);row.put("recharge_no","RC001");row.put("status","created");
        row.put("amount",new BigDecimal("10.00"));row.put("credited_amount",new BigDecimal("12.00"));row.put("payment_openid","payer-1");
        when(jdbc.queryForList(contains("where recharge_no=? and payment_mode='wechat' for update"),eq("RC001"))).thenReturn(Collections.singletonList(row));
        when(jdbc.queryForMap(contains("select balance,frozen from club_wallet"),eq(39L))).thenReturn(new HashMap<String,Object>(){{put("balance",new BigDecimal("5.00"));put("frozen",BigDecimal.ZERO);}});
        when(jdbc.update(contains("update club_recharge_order set status='success'"),eq("wx-tx-1"),eq("wx-tx-1"),eq(8L)))
            .thenAnswer(call->{row.put("status","success");row.put("mock_transaction_no","wx-tx-1");return 1;});
        Transaction tx=mock(Transaction.class,RETURNS_DEEP_STUBS);
        when(tx.getOutTradeNo()).thenReturn("RC001");when(tx.getTradeState()).thenReturn(Transaction.TradeStateEnum.SUCCESS);
        when(tx.getTransactionId()).thenReturn("wx-tx-1");when(tx.getAmount().getTotal()).thenReturn(1000);
        when(tx.getAmount().getCurrency()).thenReturn("CNY");when(tx.getPayer().getOpenid()).thenReturn("payer-1");
        return tx;
    }
    void noCredit(){verify(jdbc,never()).update(startsWith("update club_wallet set balance="),any(Object[].class));}
    @Test void duplicateVerifiedNotificationCreditsOnlyOnceIncludingConfiguredBonus(){
        Transaction tx=transaction();service.completeWechatPayment(tx);service.completeWechatPayment(tx);
        verify(pay,times(2)).validateIdentity(tx);
        verify(jdbc,times(1)).update("update club_wallet set balance=?,version=version+1 where user_id=?",new BigDecimal("17.00"),39L);
        verify(jdbc,times(1)).update(startsWith("insert into club_wallet_record"),eq(39L),eq("recharge"),eq(new BigDecimal("12.00")),eq(new BigDecimal("5.00")),eq(new BigDecimal("17.00")),eq(BigDecimal.ZERO),eq("RC001"),eq("wechat"),eq("微信充值入账"));
    }
    @Test void amountMismatchCannotCredit(){Transaction tx=transaction();when(tx.getAmount().getTotal()).thenReturn(1);assertThrows(ServiceException.class,()->service.completeWechatPayment(tx));noCredit();}
    @Test void currencyMismatchCannotCredit(){Transaction tx=transaction();when(tx.getAmount().getCurrency()).thenReturn("USD");assertThrows(ServiceException.class,()->service.completeWechatPayment(tx));noCredit();}
    @Test void payerMismatchCannotCredit(){Transaction tx=transaction();when(tx.getPayer().getOpenid()).thenReturn("another-payer");assertThrows(ServiceException.class,()->service.completeWechatPayment(tx));noCredit();}
    @Test void merchantIdentityFailureCannotCredit(){Transaction tx=transaction();doThrow(new ServiceException("商户不匹配")).when(pay).validateIdentity(tx);assertThrows(ServiceException.class,()->service.completeWechatPayment(tx));noCredit();}
    @Test void unpaidResultCannotCredit(){Transaction tx=transaction();when(tx.getTradeState()).thenReturn(Transaction.TradeStateEnum.NOTPAY);service.completeWechatPayment(tx);noCredit();}
    @Test void conflictingTransactionCannotCreditTwice(){Transaction tx=transaction();row.put("status","success");row.put("mock_transaction_no","other-tx");assertThrows(ServiceException.class,()->service.completeWechatPayment(tx));noCredit();}
    @Test void legacyRechargeCannotBeSentToWechat(){
        when(pay.enabled()).thenReturn(true);
        when(jdbc.queryForList(contains("where id=? and user_id=? for update"),eq(8L),eq(39L))).thenReturn(Collections.singletonList(Collections.singletonMap("payment_mode","legacy")));
        assertThrows(ServiceException.class,()->service.wechatRechargePrepay(39L,8L,Collections.singletonMap("idempotencyKey","retry-key")));
        verify(pay,never()).prepay(anyString(),anyString(),any(),anyString(),any());noCredit();
    }
    @Test void fakeOrderPaymentRejectedEvenIfClientClaimsSuccess(){
        assertThrows(ServiceException.class,()->service.balancePay(39L,8L,new HashMap<String,Object>(){{put("method","wechat");put("outcome","success");put("idempotencyKey","fake");}}));noCredit();
    }
}
