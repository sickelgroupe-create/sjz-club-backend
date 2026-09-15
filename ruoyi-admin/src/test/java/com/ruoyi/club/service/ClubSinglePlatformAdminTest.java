package com.ruoyi.club.service;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ClubSinglePlatformAdminTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final ClubRoleLifecycleService lifecycle=mock(ClubRoleLifecycleService.class);
    final ClubAdminService service=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),lifecycle,mock(ClubIdentityCryptoService.class));
    @Test void directShopCreationIsClosed(){assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"saveInternal","shops",new HashMap<>(),1L,null));verifyNoInteractions(db);}
    @Test void directPlayerCreationCannotBypassReview(){assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"savePlayer",null,new HashMap<>()));verifyNoInteractions(db,lifecycle);}
    @Test void userRoleEditorCannotPromoteCustomer(){when(db.queryForList(contains("from club_user where id=? for update"),eq(2L))).thenReturn(Collections.singletonList(Collections.singletonMap("user_type","user")));assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"saveUser",2L,Collections.singletonMap("user_type","player")));verifyNoInteractions(lifecycle);}
    @Test void onboardingCannotApproveWithoutIdentity(){Map<String,Object> a=new HashMap<>();a.put("status","pending");a.put("application_type","player");a.put("user_id",2L);when(db.queryForList(contains("from club_application"),eq(1L))).thenReturn(Collections.singletonList(a));assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"reviewApplication",1L,Collections.singletonMap("status","approved"),1L));verifyNoInteractions(lifecycle);}
    @Test void onboardingApprovesIdentityAndRoleTogether(){Map<String,Object> a=new HashMap<>();a.put("status","pending");a.put("application_type","player");a.put("user_id",2L);a.put("display_name","打手甲");a.put("remark","");when(db.queryForList(contains("from club_application"),eq(1L))).thenReturn(Collections.singletonList(a));when(db.queryForList(contains("select status from club_identity"),eq(2L))).thenReturn(Collections.singletonList(Collections.singletonMap("status","pending")));ReflectionTestUtils.invokeMethod(service,"reviewApplication",1L,Collections.singletonMap("status","approved"),1L);verify(db).update(contains("update club_identity set status"),eq("approved"),eq(""),eq(2L));verify(lifecycle).switchRole(2L,"player","打手甲","");}
    @Test void withdrawalWithoutRealReferenceNeverMutatesFunds(){Map<String,Object> w=new HashMap<>();w.put("status","pending");w.put("user_id",2L);w.put("amount",BigDecimal.TEN);when(db.queryForList(contains("from club_withdrawal"),eq(1L))).thenReturn(Collections.singletonList(w));Map<String,Object> wallet=new HashMap<>();wallet.put("balance",BigDecimal.ZERO);wallet.put("frozen",BigDecimal.TEN);when(db.queryForMap(contains("from club_wallet"),eq(2L))).thenReturn(wallet);assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"reviewWithdrawal",1L,Collections.singletonMap("status","approved"),1L));verify(db,never()).update(contains("update club_wallet"),any(Object[].class));}
}
