package com.ruoyi.club.service;

import java.util.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.service.payments.model.Transaction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubLogicRepairTest {
 static Map<String,Object> row(Object... values){Map<String,Object> r=new HashMap<>();for(int i=0;i<values.length;i+=2)r.put((String)values[i],values[i+1]);return r;}
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final ClubWechatPayService wx=mock(ClubWechatPayService.class);
 final ClubBusinessService business=mock(ClubBusinessService.class);
 final ClubTeenPolicyService teen=mock(ClubTeenPolicyService.class);
 final ClubAfterSaleService review=mock(ClubAfterSaleService.class);
 final ClubAppService app=new ClubAppService(db,mock(ClubAuthService.class),business,mock(ClubCatalogService.class),teen,mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),wx);
 void user(){when(db.queryForList("select id from club_user where id=? for update",68L)).thenReturn(Arrays.asList(row("id",68L)));}
 void expiry(String status,int expired){when(db.queryForList(startsWith("select status,case when payment_expires_at"),eq(8L),eq(68L))).thenReturn(Arrays.asList(row("status",status,"expired",expired)));}
 @Test void unpaidUnexpiredReadDoesNotCloseOrWrite(){user();expiry("unpaid",0);ReflectionTestUtils.invokeMethod(app,"expireUnpaidOrder",68L,8L);verifyNoInteractions(wx);verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void foreignOrderReadCannotCallExternalPayment(){user();assertThrows(ServiceException.class,()->app.order(68L,8L));verifyNoInteractions(wx);verify(db,never()).queryForList(contains("from club_payment p join club_order"),any(Object[].class));}
 @Test void paidOrderDoesNotEnterCloseFlow(){expiry("pending",1);ReflectionTestUtils.invokeMethod(app,"expireUnpaidOrder",68L,8L);verifyNoInteractions(wx);}
 @Test void expiredOwnOrderChecksEligibilityBeforeClosing(){
  expiry("unpaid",1);when(db.queryForList(contains("from club_payment p join club_order"),eq(8L))).thenReturn(Arrays.asList(row("id",4L,"payment_no","WX4","prepay_id","p","stock_reserved",1L)));
  ReflectionTestUtils.invokeMethod(app,"expireUnpaidOrder",68L,8L);
  InOrder seq=inOrder(db,wx);seq.verify(db).queryForList(startsWith("select status,case when payment_expires_at"),eq(8L),eq(68L));seq.verify(wx).close("WX4");
 }
 @Test void cancellingAnAlreadyPaidTradeCommitsDiscoveryRatherThanThrowing(){
  user();when(db.queryForList("select status from club_order where id=? and user_id=? for update",8L,68L)).thenReturn(Arrays.asList(row("status","unpaid")));
  when(db.queryForList(contains("from club_payment p join club_order"),eq(8L))).thenReturn(Arrays.asList(row("id",4L,"payment_no","WX4","prepay_id","p","stock_reserved",1L)));
  doThrow(new ServiceException("paid")).when(wx).close("WX4");Transaction tx=mock(Transaction.class);when(tx.getTradeState()).thenReturn(Transaction.TradeStateEnum.SUCCESS);when(wx.query("WX4")).thenReturn(tx);
  ClubAppService spy=spy(app);doNothing().when(spy).completeWechatPayment(tx);doReturn(row("status","pending")).when(spy).order(68L,8L);
  assertEquals("pending",spy.cancelOrder(68L,8L).get("status"));verify(spy).completeWechatPayment(tx);verify(db,never()).update(contains("set status='cancelled',cancel_reason=?"),any(Object[].class));
 }
 @Test void adminCancellationUsesSameCloseFlowAndKeepsAdminActor(){
  user();when(db.queryForList("select user_id from club_order where id=?",8L)).thenReturn(Arrays.asList(row("user_id",68L)));
  when(db.queryForList("select status from club_order where id=? and user_id=? for update",8L,68L)).thenReturn(Arrays.asList(row("status","unpaid")));
  ClubAppService spy=spy(app);doReturn(row("status","cancelled")).when(spy).order(68L,8L);
  assertEquals("cancelled",spy.adminCancelOrder(1L,8L,"用户要求取消").get("status"));
  verify(db).queryForList(contains("from club_payment p join club_order"),eq(8L));
  verify(db).update(contains("set status='cancelled',cancel_reason=?"),eq("admin_cancelled"),eq(8L));
  verify(business).releaseCoupon(8L);
  verify(db).update(contains("insert into club_order_log"),eq(8L),eq("unpaid"),eq("cancelled"),eq("admin"),eq(1L),eq("管理员取消未付款订单：用户要求取消"));
 }
 @Test void verifiedWechatChargeSurvivesPolicyLimitAndOpensReviewOnce(){
  user();ReflectionTestUtils.setField(app,"afterSale",review);
  Map<String,Object> payment=row("id",4L,"order_id",8L,"user_id",68L,"status","created","amount",new BigDecimal("60"),"stock_reserved",1L);
  when(db.queryForList(startsWith("select user_id,order_id from club_payment"),eq("WX4"))).thenReturn(Arrays.asList(row("user_id",68L,"order_id",8L)));
  when(db.queryForList("select * from club_payment where payment_no=? and mode='wechat' for update","WX4")).thenReturn(Arrays.asList(payment));
  when(db.query(contains("select openid"),any(RowMapper.class),eq(68L))).thenReturn(Arrays.asList("payer"));
  when(db.queryForMap("select * from club_order where id=? for update",8L)).thenReturn(row("status","unpaid","total_amount",new BigDecimal("60"),"quantity",1,"product_id",2L));
  when(teen.recordConfirmedWechatSpend(68L,new BigDecimal("60"))).thenReturn(true);
  when(db.update(startsWith("update club_payment set status='success'"),eq("TX4"),eq(4L))).thenAnswer(i->{payment.put("status","success");payment.put("mock_transaction_no","TX4");return 1;});
  Transaction tx=mock(Transaction.class,RETURNS_DEEP_STUBS);when(tx.getOutTradeNo()).thenReturn("WX4");when(tx.getTradeState()).thenReturn(Transaction.TradeStateEnum.SUCCESS);when(tx.getTransactionId()).thenReturn("TX4");when(tx.getAmount().getTotal()).thenReturn(6000);when(tx.getPayer().getOpenid()).thenReturn("payer");
  app.completeWechatPayment(tx);app.completeWechatPayment(tx);
  verify(teen,never()).reserveSuccessfulSpend(anyLong(),any());verify(review,times(1)).reviewPaidLimit(8L);
  InOrder seq=inOrder(db,review);seq.verify(db).update(startsWith("update club_payment set status='success'"),eq("TX4"),eq(4L));seq.verify(review).reviewPaidLimit(8L);
 }
 @Test void missingRegistrationProofCannotWriteAccount(){ClubAuthService auth=new ClubAuthService(db,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());assertThrows(ServiceException.class,()->auth.phoneRegister(row("phone","13800000000","password","Password123")));verifyNoInteractions(db);}
 @Test void authorizedRegistrationMustMatchTypedPhone(){ClubAuthService auth=new ClubAuthService(db,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);ReflectionTestUtils.setField(auth,"wechatPhone",phone);when(phone.verifiedPhone("code")).thenReturn("13900000000");assertThrows(ServiceException.class,()->auth.phoneRegister(row("phone","13800000000","password","Password123","phoneCode","code")));verifyNoInteractions(db);}
 @Test void rejectionCreatesReviewAndNeverPretendsWechatRefundSucceeded(){
  ClubAfterSaleService after=new ClubAfterSaleService(db,business,wx);when(db.queryForList(contains("provider_user_id=? and provider_type='player' for update"),eq(8L),eq(75L))).thenReturn(Arrays.asList(row("id",8L,"user_id",68L,"status","pending","provider_user_id",75L,"total_amount",new BigDecimal("60"))));
  after.rejectOrder(75L,8L,row("idempotencyKey","reject"));verify(db).update("update club_order set status='refunding',version=version+1 where id=?",8L);verifyNoInteractions(wx,business);
 }
 @Test void rejectionCannotAccessAnotherPlayersOrder(){ClubAfterSaleService after=new ClubAfterSaleService(db,business,wx);assertThrows(com.ruoyi.club.web.ClubForbiddenException.class,()->after.rejectOrder(75L,8L,row("idempotencyKey","reject")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void servicingRefundCaseStillOccupiesPlayer(){String sql=ClubPlayerCapacity.busy("p.user_id");assertTrue(sql.contains("status='refunding'"));assertTrue(sql.contains("in ('accepted','serving')"));assertTrue(sql.contains("provider_completed_at is null"));}
 @Test void restoringServiceCannotOverlapAnotherService(){
  ClubAfterSaleService after=new ClubAfterSaleService(db,business,wx);when(db.queryForList(startsWith("select from_status"),eq(8L))).thenReturn(Arrays.asList(row("from_status","serving")));
  when(db.queryForMap(startsWith("select provider_user_id,provider_type"),eq(8L))).thenReturn(row("provider_user_id",75L,"provider_type","player"));
  when(db.queryForList("select id from club_user where id=? for update",75L)).thenReturn(Arrays.asList(row("id",75L)));
  when(db.queryForList(contains("capacity_order.id<>? for update"),eq(75L),eq(8L))).thenReturn(Arrays.asList(row("id",9L)));
  assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(after,"restoreOrderAfterRejected",8L));verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void pendingWechatReservationCountsTowardsDailyLimit(){
  ClubTeenPolicyService policy=spy(new ClubTeenPolicyService(db));doNothing().when(policy).assertCanPay(anyLong(),any(),anyString());
  when(db.queryForObject(contains("club_teen_setting"),eq(Integer.class),eq(68L))).thenReturn(1);
  when(db.query(contains("config_value"),any(RowMapper.class),eq("teen_daily_spend_limit"))).thenReturn(Arrays.asList("100"));
  when(db.queryForList(contains("stock_reserved=1 and order_id<>? for update"),eq(68L),eq(8L))).thenReturn(Arrays.asList(row("amount",new BigDecimal("60"))));
  assertThrows(ServiceException.class,()->policy.assertPaymentCapacity(68L,8L,new BigDecimal("60"),"wechat"));assertDoesNotThrow(()->policy.assertPaymentCapacity(68L,8L,new BigDecimal("40"),"wechat"));
 }
 @Test void alreadyChargedLimitOverrunIsRecordedNotThrown(){
  ClubTeenPolicyService policy=new ClubTeenPolicyService(db);when(db.queryForObject(contains("club_teen_setting"),eq(Integer.class),eq(68L))).thenReturn(1);
  when(db.query(contains("config_value"),any(RowMapper.class),eq("teen_daily_spend_limit"))).thenReturn(Arrays.asList("100"));
  when(db.queryForObject(startsWith("select used_amount"),eq(BigDecimal.class),eq(68L))).thenReturn(new BigDecimal("60"));
  assertTrue(policy.recordConfirmedWechatSpend(68L,new BigDecimal("60")));verify(db).update(startsWith("update club_teen_daily_spend"),eq(new BigDecimal("120")),eq(68L));
 }
 @Test void repeatedWechatPrepayReusesOnlyOnePendingTrade(){
  user();when(wx.enabled()).thenReturn(true);
  when(db.queryForList(startsWith("select o.*,p.name product_name"),eq(8L),eq(68L))).thenReturn(Arrays.asList(row("status","unpaid","payment_expires_at",new Timestamp(System.currentTimeMillis()+600000),"total_amount",BigDecimal.ONE,"product_name","service")));
  when(db.query(contains("select openid"),any(RowMapper.class),eq(68L))).thenReturn(Arrays.asList("payer"));
  when(db.queryForList(startsWith("select id,payment_no,stock_reserved,prepay_id from club_payment where user_id"),eq(68L),eq(8L))).thenReturn(Arrays.asList(row("id",4L,"payment_no","WX4","stock_reserved",1L)));
  when(wx.prepay(eq("WX4"),anyString(),any(),eq("payer"),any())).thenReturn(row("package","prepay_id=p"));
  assertEquals("WX4",app.wechatPrepay(68L,8L,row("idempotencyKey","same")).get("paymentNo"));assertEquals("WX4",app.wechatPrepay(68L,8L,row("idempotencyKey","same")).get("paymentNo"));
  verify(db,never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.support.KeyHolder.class));
  verify(db,never()).update(startsWith("update club_product_sku set stock=stock-"),any(Object[].class));
 }
 @Test void closedWechatAttemptCanCreateNewAttemptWithoutReusingItsUniqueKey()throws Exception {
  user();when(wx.enabled()).thenReturn(true);
  when(db.queryForList(startsWith("select o.*,p.name product_name"),eq(8L),eq(68L))).thenReturn(Arrays.asList(row("status","unpaid","payment_expires_at",new Timestamp(System.currentTimeMillis()+600000),"total_amount",BigDecimal.ONE,"product_name","service")));
  when(db.query(contains("select openid"),any(RowMapper.class),eq(68L))).thenReturn(Arrays.asList("payer"));
  java.sql.Connection connection=mock(java.sql.Connection.class);java.sql.PreparedStatement statement=mock(java.sql.PreparedStatement.class);when(connection.prepareStatement(anyString(),eq(java.sql.Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
  when(db.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.support.KeyHolder.class))).thenAnswer(i->{i.<org.springframework.jdbc.core.PreparedStatementCreator>getArgument(0).createPreparedStatement(connection);i.<org.springframework.jdbc.support.KeyHolder>getArgument(1).getKeyList().add(row("id",5L));return 1;});
  when(db.queryForList("select id,payment_no,stock_reserved,prepay_id from club_payment where id=? for update",5L)).thenReturn(Arrays.asList(row("stock_reserved",1L)));
  when(wx.prepay(anyString(),anyString(),any(),eq("payer"),any())).thenReturn(row("package","prepay_id=p"));
  app.wechatPrepay(68L,8L,row("idempotencyKey","closed-ui-key"));
  verify(statement).setObject(eq(6),startsWith("wechat:WX"));verify(statement,never()).setObject(anyInt(),eq("closed-ui-key"));
 }
 @Test void switchingToBalanceFirstReconcilesAnAlreadySuccessfulWechatTrade(){
  user();ReflectionTestUtils.setField(app,"paymentMode","wechat");when(db.queryForList(startsWith("select o.*,case when o.payment_expires_at"),eq(8L),eq(68L))).thenReturn(Arrays.asList(row("status","unpaid","payment_expired",0L)));
  when(db.queryForList(contains("from club_payment p join club_order"),eq(8L))).thenReturn(Arrays.asList(row("id",4L,"payment_no","WX4","prepay_id","p","stock_reserved",1L)));
  doThrow(new ServiceException("paid")).when(wx).close("WX4");Transaction tx=mock(Transaction.class);when(tx.getTradeState()).thenReturn(Transaction.TradeStateEnum.SUCCESS);when(wx.query("WX4")).thenReturn(tx);
  ClubAppService spy=spy(app);doNothing().when(spy).completeWechatPayment(tx);doReturn(row("status","pending")).when(spy).order(68L,8L);
  assertEquals(true,spy.balancePay(68L,8L,row("method","balance","idempotencyKey","same-ui-key")).get("alreadyPaid"));verify(db,never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.support.KeyHolder.class));verify(db,never()).update(startsWith("update club_wallet"),any(Object[].class));
  verify(db).queryForList(contains("p.mode=?"),eq(68L),eq(8L),eq("balance"),startsWith("balance:"));
 }
}
