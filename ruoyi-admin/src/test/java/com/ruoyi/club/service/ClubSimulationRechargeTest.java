package com.ruoyi.club.service;
import java.util.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubSimulationRechargeTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final ClubWechatPayService wx=mock(ClubWechatPayService.class);
 final ClubTeenPolicyService teen=mock(ClubTeenPolicyService.class);
 final ClubAppService service=spy(new ClubAppService(db,mock(ClubAuthService.class),mock(ClubBusinessService.class),mock(ClubCatalogService.class),teen,mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),wx));
 final Map<String,Object> recharge=row("id",8L,"user_id",39L,"payment_mode","mock_wechat","status","created","created_at",new Timestamp(System.currentTimeMillis()),"credited_amount",new BigDecimal("12.00"),"recharge_no","RC-TEST");
 static Map<String,Object> row(Object... a){Map<String,Object> r=new HashMap<>();for(int i=0;i<a.length;i+=2)r.put((String)a[i],a[i+1]);return r;}
 ClubSimulationRechargeTest(){
  ReflectionTestUtils.setField(service,"paymentMode","simulation");
  when(db.queryForList("select id from club_user where id=? for update",39L)).thenReturn(Arrays.asList(row("id",39L)));
  when(db.queryForList("select * from club_recharge_order where id=? and user_id=? for update",8L,39L)).thenReturn(Arrays.asList(recharge));
  when(db.queryForMap("select balance,frozen from club_wallet where user_id=? for update",39L)).thenReturn(row("balance",new BigDecimal("5.00"),"frozen",BigDecimal.ZERO));
  when(db.update(startsWith("update club_recharge_order set status='success'"),eq("SIM-RECHARGE-8"),eq("SIM-RECHARGE-8"),eq(8L))).thenAnswer(a->{recharge.put("status","success");return 1;});
  doReturn(recharge).when(service).recharge(39L,8L);doReturn(row("balance",new BigDecimal("17.00"))).when(service).wallet(39L);
 }
 void noCredit(){verify(db,never()).update(startsWith("update club_wallet set balance="),any(Object[].class));verifyNoInteractions(wx);}
 @Test void duplicateRequestWithDifferentKeysCreditsOnlyOnceFromSavedAmount(){
  assertEquals(true,service.simulateRecharge(39L,8L,row("idempotencyKey","first","amount","99999")).get("paid"));
  assertEquals(true,service.simulateRecharge(39L,8L,row("idempotencyKey","retry")).get("paid"));
  verify(db,times(1)).update("update club_wallet set balance=?,version=version+1 where user_id=?",new BigDecimal("17.00"),39L);
  verify(db,times(1)).update(startsWith("insert into club_wallet_record"),eq(39L),eq("recharge"),eq(new BigDecimal("12.00")),eq(new BigDecimal("5.00")),eq(new BigDecimal("17.00")),eq(BigDecimal.ZERO),eq("RC-TEST"),eq("mock_wechat"),eq("模拟充值入账（无真实资金）"));
  verifyNoInteractions(wx);
 }
 @Test void anotherUsersRechargeIsNotPayable(){assertThrows(ServiceException.class,()->service.simulateRecharge(39L,999L,row("idempotencyKey","foreign")));noCredit();}
 @Test void realRechargeCannotBeSimulated(){recharge.put("payment_mode","wechat");assertThrows(ServiceException.class,()->service.simulateRecharge(39L,8L,row("idempotencyKey","real")));noCredit();}
 @Test void expiredRechargeClosesWithoutCrediting(){recharge.put("created_at",new Timestamp(System.currentTimeMillis()-3600000));assertEquals(true,service.simulateRecharge(39L,8L,row("idempotencyKey","expired")).get("expired"));noCredit();}
 @Test void cancelledRechargeCannotBeCredited(){recharge.put("status","cancelled");assertThrows(ServiceException.class,()->service.simulateRecharge(39L,8L,row("idempotencyKey","cancelled")));noCredit();}
 @Test void missingKeyRejected(){assertThrows(ServiceException.class,()->service.simulateRecharge(39L,8L,Collections.emptyMap()));noCredit();}
 @Test void teenRestrictionDoesNotCreditWallet(){doThrow(new ServiceException("青少年限制")).when(teen).assertCanRecharge(39L);assertThrows(ServiceException.class,()->service.simulateRecharge(39L,8L,row("idempotencyKey","teen")));noCredit();}
 @Test void sandboxRejectsAllNativePaymentPathsBeforeExternalCall(){
  assertThrows(ServiceException.class,()->service.wechatPrepay(39L,8L,Collections.emptyMap()));
  assertThrows(ServiceException.class,()->service.wechatRechargePrepay(39L,8L,Collections.emptyMap()));
  assertThrows(ServiceException.class,()->service.syncWechatPayment(39L,8L));
  assertThrows(ServiceException.class,()->service.syncWechatRecharge(39L,8L));
  assertThrows(ServiceException.class,()->service.completeWechatPayment(null));noCredit();
 }
}
