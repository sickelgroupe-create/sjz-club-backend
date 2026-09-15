package com.ruoyi.club.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ClubPlayerCatalogTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);
 ClubAdminService admin(){return new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));}
 ClubAppService app(){return new ClubAppService(db,mock(ClubAuthService.class),mock(ClubBusinessService.class),mock(ClubCatalogService.class),mock(ClubTeenPolicyService.class),mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),mock(ClubWechatPayService.class));}
 @Test void selectedCategoryMustExistAndBeActive(){Map<String,Object> p=new HashMap<>();p.put("target_kind","category");p.put("target_category","hour");assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin(),"homeEntryTarget",null,p));when(db.queryForObject(contains("from club_category"),eq(Integer.class),eq("hour"))).thenReturn(1);assertEquals("/pages/product/list?category=hour",ReflectionTestUtils.invokeMethod(admin(),"homeEntryTarget",null,p));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void standardDestinationsIgnoreArbitraryRawUrls(){Map<String,Object> p=new HashMap<>();p.put("target_kind","players");p.put("target_url","/evil");assertEquals("/pages/players/index",ReflectionTestUtils.invokeMethod(admin(),"homeEntryTarget",null,p));verifyNoInteractions(db);}
 @Test void preserveCanOnlyReadAnExistingStoredDestination(){Map<String,Object> p=Collections.singletonMap("target_kind","preserve");assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin(),"homeEntryTarget",null,p));when(db.queryForObject("select target_url from club_home_entry where id=?",String.class,1L)).thenReturn("/pages/news/index");assertEquals("/pages/news/index",ReflectionTestUtils.invokeMethod(admin(),"homeEntryTarget",1L,p));}
 @Test void publicOffersRequireActiveOnlineApprovedPlayerAndPositiveStock(){app().playerOffers(7L);verify(db).queryForList(argThat(sql->sql.contains("pr.bound_player_id=pp.id")&&sql.contains("sk.stock>0")&&sql.contains("pp.online_status=1")&&sql.contains("club_application")&&sql.contains("sk.price")),eq(7L));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void cataloguePriceComesFromCheapestPurchasableSkuNotFirstId(){app().playerItems("male","",1,20,"default",null,null);verify(db).queryForList(argThat(sql->sql.contains("ps.price as minPrice")&&sql.contains("order by skx.price,skx.id limit 1")&&sql.contains("skx.stock>0")),(Object)isNull(),isNull(),isNull(),isNull(),eq("male"),eq("male"),eq(""),eq("%%"),eq(20),eq(0));}
 @Test void ownProductsAreReadOnlyAndScopedToAuthenticatedPlayer(){when(db.query(contains("select user_type"),any(RowMapper.class),eq(75L))).thenReturn(Collections.singletonList("player"));new ClubBusinessService(db,mock(ClubCatalogService.class)).playerProducts(75L);verify(db).queryForList(argThat(sql->sql.contains("pp.user_id=?")&&sql.contains("pr.status<>'deleted'")&&sql.contains("pr.bound_player_id")&&sql.contains("skuStatus")),eq(75L));verify(db,never()).update(anyString(),any(Object[].class));}
}
