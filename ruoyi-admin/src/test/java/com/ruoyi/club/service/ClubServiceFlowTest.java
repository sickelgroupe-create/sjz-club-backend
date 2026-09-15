package com.ruoyi.club.service;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubServiceFlowTest {
 final JdbcTemplate db=mock(JdbcTemplate.class,invocation->invocation.getMethod().getName().equals("update")?1:RETURNS_DEFAULTS.answer(invocation));
 boolean wrote(String sql){return mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")&&String.valueOf((Object)i.getArgument(0)).contains(sql));}
 final ClubBusinessService business=new ClubBusinessService(db,mock(ClubCatalogService.class));
 Map<String,Object> order(String status){Map<String,Object> o=new HashMap<>();o.put("id",8L);o.put("status",status);o.put("provider_type","player");o.put("provider_user_id",75L);o.put("user_id",68L);o.put("total_amount",new BigDecimal("99"));return o;}
 void available(){when(db.queryForList("select id from club_user where id=? for update",75L)).thenReturn(Collections.singletonList(Collections.singletonMap("id",75L)));}
 void busy(){available();when(db.queryForList(contains("capacity_order.id<>? for update"),eq(75L),any())).thenReturn(Collections.singletonList(Collections.singletonMap("id",9L)));}
 void locked(String status){when(db.queryForList("select * from club_order where id=? for update",8L)).thenReturn(Collections.singletonList(order(status)));}
 @Test void capacityUsesPlayerLockThenCurrentRead(){available();ClubPlayerCapacity.assertAvailable(db,75L,8L);InOrder sequence=inOrder(db);sequence.verify(db).queryForList("select id from club_user where id=? for update",75L);sequence.verify(db).queryForList(contains("capacity_order.id<>? for update"),eq(75L),eq(8L));}
 @Test void busyPlayerCannotAcceptOrStartAnotherOrder(){for(String target:Arrays.asList("accepted","serving")){reset(db);locked("pending");busy();assertThrows(ServiceException.class,()->business.adminTransition(8L,target,"",1L));assertFalse(wrote(""));}}
 @Test void playerCanStartPendingOrderWithoutAdminAcceptance(){
  available();when(db.query(contains("select user_type"),any(RowMapper.class),eq(75L))).thenReturn(Collections.singletonList("player"));
  when(db.queryForList(startsWith("select o.* from club_order"),eq(8L),eq(75L))).thenReturn(Collections.singletonList(order("pending")));
  when(db.queryForList(contains("as playerBusy,"),eq(8L),eq(75L))).thenReturn(Collections.singletonList(order("serving")));
  
  Map<String,Object> input=new HashMap<>();input.put("action","start");input.put("idempotencyKey","start-8");
  business.roleAction(75L,8L,input);
  verify(db).update("update club_order set status=?,version=version+1 where id=? and status=?","serving",8L,"pending");
 }
 @Test void workbenchShowsDirectStartAndSuppressesItWhenBusy(){Map<String,Object> o=order("pending");assertEquals(Arrays.asList("start","reject"),ReflectionTestUtils.invokeMethod(business,"allowedActions",o));o.put("playerBusy",1L);assertEquals(Collections.singletonList("reject"),ReflectionTestUtils.invokeMethod(business,"allowedActions",o));o.put("status","accepted");assertEquals(Collections.emptyList(),ReflectionTestUtils.invokeMethod(business,"allowedActions",o));}
 @Test void onlyAcceptedOrderCanBeRequeuedAndReasonIsMandatory(){for(String state:Arrays.asList("serving","completed","unpaid")){locked(state);assertThrows(ServiceException.class,()->business.adminTransition(8L,"pending","暂时无法服务",1L));}locked("accepted");assertThrows(ServiceException.class,()->business.adminTransition(8L,"pending","",1L));assertFalse(wrote(""));}
 @Test void requeuePreservesPayment(){locked("accepted");business.adminTransition(8L,"pending","等待安排",1L);verify(db).update("update club_order set status=?,version=version+1 where id=? and status=?","pending",8L,"accepted");assertFalse(wrote("club_payment"));assertFalse(wrote("club_wallet"));}
 @Test void abandonCreatesReviewWithoutMovingMoney(){locked("serving");ClubBusinessService b=mock(ClubBusinessService.class);ClubWechatPayService wx=mock(ClubWechatPayService.class);ClubAfterSaleService a=new ClubAfterSaleService(db,b,wx);Map<String,Object> input=new HashMap<>();input.put("note","无法继续服务");input.put("requestId","abandon-8");a.adminAbandon(1L,8L,input);verify(db).update(contains("update club_order set status='refunding'"),eq(8L),eq("serving"));assertTrue(wrote("insert into club_aftersale("));verifyNoInteractions(b,wx);assertFalse(wrote("club_wallet"));assertFalse(wrote("club_payment"));}
 @Test void abandonCannotApplyToUnpaidOrCompletedOrder(){ClubAfterSaleService a=new ClubAfterSaleService(db,business,mock(ClubWechatPayService.class));Map<String,Object> input=new HashMap<>();input.put("note","无法服务");input.put("requestId","abandon-8");for(String status:Arrays.asList("unpaid","completed","refunded")){locked(status);assertThrows(ServiceException.class,()->a.adminAbandon(1L,8L,input));}assertFalse(wrote(""));}
 @Test void phoneIsRequiredOnServerBeforeAnyOrderWrite(){
  Map<String,Object> item=new HashMap<>();item.put("product_status","active");item.put("sku_status","active");item.put("shop_status","active");item.put("stock",10);
  when(db.queryForList(startsWith("select p.id as product_id"),eq(1L),eq(2L))).thenReturn(Collections.singletonList(item));
  ClubAppService app=new ClubAppService(db,mock(ClubAuthService.class),business,mock(ClubCatalogService.class),mock(ClubTeenPolicyService.class),mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),mock(ClubWechatPayService.class));
  Map<String,Object> form=new HashMap<>();form.put("gameId","G");form.put("gameName","顾客");Map<String,Object> input=new HashMap<>();input.put("clientRequestId","new-order");input.put("productId",1L);input.put("skuId",2L);input.put("form",form);
  assertTrue(assertThrows(ServiceException.class,()->app.createOrder(68L,input)).getMessage().contains("联系电话"));assertFalse(wrote(""));
 }
 @Test void customerServiceCannotBeSavedAsProductCategory(){ClubAdminService admin=new ClubAdminService(db,business,mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));Map<String,Object> input=new HashMap<>();input.put("code","service");input.put("name","在线客服");when(db.queryForObject(anyString(),eq(Integer.class),eq("service"),eq(0L))).thenReturn(0);assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin,"saveCategory",null,input));assertFalse(wrote(""));}
}
