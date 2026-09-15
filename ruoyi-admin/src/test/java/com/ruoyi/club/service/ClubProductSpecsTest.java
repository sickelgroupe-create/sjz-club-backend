package com.ruoyi.club.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ClubProductSpecsTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 final ClubProductSpecs specs=new ClubProductSpecs(db);
 Map<String,Object> row(Long id){Map<String,Object> r=new LinkedHashMap<>();r.put("id",id);r.put("name","一小时");r.put("price","50.00");r.put("stock",5);r.put("status","active");return r;}
 Map<String,Object> editing(Map<String,Object> original,List<Map<String,Object>> submitted){
  List<Map<String,Object>> stored=Collections.singletonList(original);
  when(db.queryForList(contains("from club_product where"),eq(7L))).thenReturn(Collections.singletonList(Collections.singletonMap("id",7L)));
  when(db.queryForList(contains("from club_product_sku"),eq(7L))).thenReturn(stored);
  Map<String,Object> p=new HashMap<>();p.put("skus",submitted);p.put("sku_version",ClubProductSpecs.version(stored));return p;
 }
 @Test void changedStockRejectsStaleEditorBeforeAnyWrite(){Map<String,Object> p=editing(row(8L),Collections.singletonList(row(8L)));p.put("sku_version","old");assertThrows(ServiceException.class,()->specs.prepare(7L,p));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void cannotMoveAnotherProductsSpecification(){Map<String,Object> p=editing(row(8L),Collections.singletonList(row(9L)));assertThrows(ServiceException.class,()->specs.prepare(7L,p));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void cannotOmitOrDuplicateExistingSpecifications(){
  assertThrows(ServiceException.class,()->specs.prepare(7L,editing(row(8L),Collections.singletonList(row(null)))));
  assertThrows(ServiceException.class,()->specs.prepare(7L,editing(row(8L),Arrays.asList(row(8L),row(8L)))));
 }
 @Test void existingSpecificationsDisableWithoutDeletingOrRebinding(){Map<String,Object> disabled=row(8L);disabled.put("status","inactive");List<Map<String,Object>> parsed=specs.prepare(7L,editing(row(8L),Collections.singletonList(disabled)));specs.save(7L,parsed);verify(db).update(eq("update club_product_sku set name=?,price=?,stock=?,status=? where id=? and product_id=?"),eq("一小时"),eq(new java.math.BigDecimal("50.00")),eq(5),eq("inactive"),eq(8L),eq(7L));}
 @Test void newSpecificationRequiresValidPositivePriceIntegralStockAndState(){for(String field:Arrays.asList("price","stock","status","name")){Map<String,Object> r=row(null);r.put(field,"price".equals(field)?"0":"stock".equals(field)?"1.5":"");assertThrows(ServiceException.class,()->specs.prepare(null,Collections.singletonMap("skus",Collections.singletonList(r))));}verifyNoInteractions(db);}
 @Test void newSpecificationIdsCannotClaimOtherProducts(){assertThrows(ServiceException.class,()->specs.prepare(null,Collections.singletonMap("skus",Collections.singletonList(row(8L)))));verifyNoInteractions(db);}
 @Test void specificationsAndIdentityCanNoLongerBeSavedSeparately(){ClubAdminService admin=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));for(String type:Arrays.asList("skus","identities"))assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin,"saveInternal",type,Collections.singletonMap("status","approved"),1L,7L));verifyNoInteractions(db);}
 @Test void readinessExplainsMissingProductStockAndOnlineWithoutChangingRecords(){Map<String,Object> p=row(7L);p.put("admission_ready",1);p.put("display_name","打手");p.put("image","image");p.put("intro","简介");p.put("voice_url","voice");p.put("has_product",1);p.put("has_sku",1);p.put("has_stock",0);p.put("online_status",0);p.put("service_status","offline");String reason=ClubPlayerReadiness.describe(p);assertTrue(reason.contains("库存不足"));assertTrue(reason.contains("尚未开启"));assertFalse(reason.contains("缺少头像"));p.put("has_stock",1);p.put("online_status",1);p.put("service_status","online");p.put("gender","female");assertEquals("可接单 · 女生分类",ClubPlayerReadiness.describe(p));}
 @Test void productSaveOwnsAllSpecificationUpdatesAndSynchronizesSummary(){
  Map<String,Object> original=row(8L),changed=row(8L);changed.put("price","80.00");
  Map<String,Object> input=editing(original,Collections.singletonList(changed));input.put("name","小时服务");input.put("bound_player_id",12L);input.put("category_code","hour");input.put("status","active");
  when(db.queryForObject(contains("select min(id) from club_shop"),eq(Long.class))).thenReturn(1L);
  when(db.queryForObject(contains("from club_player_profile p"),eq(Integer.class),eq(12L))).thenReturn(1);
  when(db.queryForObject(contains("from club_shop where id"),eq(Integer.class),eq(1L))).thenReturn(1);
  when(db.queryForObject(contains("from club_category where"),eq(Integer.class),eq("hour"))).thenReturn(1);
  ClubCatalogService catalog=mock(ClubCatalogService.class);
  ClubAdminService admin=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),catalog,mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));
  ReflectionTestUtils.invokeMethod(admin,"saveProduct",7L,input);
  verify(catalog).validatePriceAndStock(new java.math.BigDecimal("80.00"),5);
  verify(catalog).syncProductSummary(7L);
  verify(db).update(eq("update club_product_sku set name=?,price=?,stock=?,status=? where id=? and product_id=?"),eq("一小时"),eq(new java.math.BigDecimal("80.00")),eq(5),eq("active"),eq(8L),eq(7L));
  verify(db,never()).queryForList(eq("select id from club_product_sku where product_id=? and status='active' order by id"),eq(7L));
 }
 @Test void retiredDeletionCannotAlterHistoricalRecords(){ClubAdminService admin=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));for(String type:Arrays.asList("skus","identities","playerServices"))assertThrows(ServiceException.class,()->admin.delete(type,1L,1L,"清理","request"));verifyNoInteractions(db);}
}
