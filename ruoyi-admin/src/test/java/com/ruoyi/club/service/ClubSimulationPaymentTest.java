package com.ruoyi.club.service;
import java.util.*;
import java.sql.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ClubSimulationPaymentTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final ClubWechatPayService wx=mock(ClubWechatPayService.class);
 final ClubBusinessService business=mock(ClubBusinessService.class);
 final ClubTeenPolicyService teen=mock(ClubTeenPolicyService.class);
 final ClubAppService app=spy(new ClubAppService(db,mock(ClubAuthService.class),business,mock(ClubCatalogService.class),teen,mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),wx));
 ClubSimulationPaymentTest(){
  ReflectionTestUtils.setField(app,"paymentMode","simulation");
  when(db.queryForList("select id from club_user where id=? for update",68L)).thenReturn(Arrays.asList(row("id",68L)));
  doReturn(row("id",8L,"status","pending")).when(app).order(68L,8L);
 }
 static Map<String,Object> row(Object... a){Map<String,Object> r=new HashMap<>();for(int i=0;i<a.length;i+=2)r.put((String)a[i],a[i+1]);return r;}
 PreparedStatement payable() throws Exception {
  when(db.queryForList(startsWith("select o.*,case when o.payment_expires_at"),eq(8L),eq(68L))).thenReturn(Arrays.asList(row("id",8L,"status","unpaid","payment_expired",0L,"total_amount",new BigDecimal("99.00"),"quantity",1,"sku_id",2L,"product_id",1L,"order_no","TEST-8")));
  when(db.update(startsWith("update club_product_sku set stock=stock-?"),eq(1),eq(2L),eq(1))).thenReturn(1);
  Connection c=mock(Connection.class);PreparedStatement p=mock(PreparedStatement.class);
  when(c.prepareStatement(anyString(),eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(p);
  when(db.update(any(PreparedStatementCreator.class),any(KeyHolder.class))).thenAnswer(a->{a.<PreparedStatementCreator>getArgument(0).createPreparedStatement(c);a.<KeyHolder>getArgument(1).getKeyList().add(row("id",4L));return 1;});
  return p;
 }
 @Test void simulatedSuccessUsesSavedAmountAndRecordsStockCouponAndAuditWithoutExternalPayment() throws Exception {
  PreparedStatement p=payable();
  Map<String,Object> result=app.balancePay(68L,8L,row("method","wechat","idempotencyKey","test-success","amount","0.01"));
  assertEquals("success",((Map<?,?>)result.get("payment")).get("status"));
  assertEquals("mock_wechat",((Map<?,?>)result.get("payment")).get("method"));
  verify(p).setObject(4,new BigDecimal("99.00"));
  verify(business).consumeReservedCoupon(8L);
  verify(db).update(startsWith("update club_order set status='pending'"),eq("mock_wechat"),eq(8L));
  verify(db,never()).update(startsWith("update club_wallet"),any(Object[].class));
  verifyNoInteractions(wx);
 }
 @Test void failedAttemptCannotConsumeStockCouponOrBalance() throws Exception {
  payable();Map<String,Object> result=app.balancePay(68L,8L,row("method","wechat","idempotencyKey","test-failed","outcome","failed"));
  assertEquals("failed",((Map<?,?>)result.get("payment")).get("status"));
  verify(db,never()).update(startsWith("update club_product_sku"),any(Object[].class));
  verify(db,never()).update(startsWith("update club_wallet"),any(Object[].class));
  verify(business,never()).consumeReservedCoupon(anyLong());verifyNoInteractions(wx);
 }
 @Test void cancelledAttemptCannotMakeOrderPaid() throws Exception {
  payable();app.balancePay(68L,8L,row("method","wechat","idempotencyKey","test-cancel","outcome","cancelled"));
  verify(db).update("update club_payment set status=? where id=?","cancelled",4L);
  verify(db,never()).update(startsWith("update club_order set status='pending'"),any(Object[].class));
  verifyNoInteractions(wx);
 }
 @Test void retryReusesSavedPaymentWithoutAnotherWrite() {
  when(db.queryForList(contains("p.mode=? and p.idempotency_key=?"),eq(68L),eq(8L),eq("mock_wechat"),anyString())).thenReturn(Arrays.asList(row("id",4L,"status","success")));
  assertEquals(true,app.balancePay(68L,8L,row("method","wechat","idempotencyKey","same")).get("idempotent"));
  verify(db,never()).update(any(PreparedStatementCreator.class),any(KeyHolder.class));verifyNoInteractions(wx,business);
 }
 @Test void foreignOrderCannotBePaid() {
  assertThrows(ServiceException.class,()->app.balancePay(68L,999L,row("method","wechat","idempotencyKey","foreign")));
  verify(db,never()).update(any(PreparedStatementCreator.class),any(KeyHolder.class));verifyNoInteractions(wx,business);
 }
 @Test void outcomeAndKeyValidationCannotBeBypassed() {
  assertThrows(ServiceException.class,()->app.balancePay(68L,8L,row("method","wechat","idempotencyKey","bad","outcome","admin_paid")));
  assertThrows(ServiceException.class,()->app.balancePay(68L,8L,row("method","wechat")));
  verify(db,never()).update(any(PreparedStatementCreator.class),any(KeyHolder.class));verifyNoInteractions(wx);
 }
 @Test void liveModeCannotEnableSimulationByClientInput() {
  ReflectionTestUtils.setField(app,"paymentMode","wechat");
  assertThrows(ServiceException.class,()->app.balancePay(68L,8L,row("method","wechat","idempotencyKey","test","outcome","success")));
  verify(db,never()).update(any(PreparedStatementCreator.class),any(KeyHolder.class));verifyNoInteractions(wx);
 }
}
