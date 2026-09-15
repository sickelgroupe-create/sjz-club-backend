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

class ClubOperatorRulesTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    ClubBusinessService business(Map<String,Object> profile){
        when(db.queryForList("select id from club_user where id=? for update",75L)).thenReturn(Collections.singletonList(Collections.singletonMap("id",75L)));
        when(db.query(contains("select user_type"),any(RowMapper.class),eq(75L))).thenReturn(Collections.singletonList("player"));
        when(db.queryForList(contains("display_name as displayName,gender"),eq(75L))).thenReturn(Collections.singletonList(profile));
        when(db.queryForObject(anyString(),eq(Integer.class),eq(75L))).thenReturn(1);
        ClubBusinessService service=spy(new ClubBusinessService(db,mock(ClubCatalogService.class)));
        doReturn(new HashMap<>()).when(service).workbench(75L);return service;
    }
    Map<String,Object> profile(){Map<String,Object> p=new HashMap<>();p.put("status","active");p.put("displayName","打手");p.put("image","/a.png");p.put("intro","简介");p.put("voiceUrl","/a.mp3");return p;}
    @Test void productLevelBindingAllowsGoingOnlineWithoutLegacySkuBinding(){
        business(profile()).updateAvailability(75L,Collections.singletonMap("status","online"));
        verify(db).queryForObject(contains("pr.bound_player_id=p.id and pr.status='active'"),eq(Integer.class),eq(75L));
        verify(db).update("update club_player_profile set online_status=? where user_id=?",1,75L);
        assertFalse(ClubPlayerEligibility.activeSkus("p").contains("club_player_service"));
        assertFalse(ClubPlayerEligibility.activeSkus("p").contains("club_shop"));
    }
    @Test void noActiveProductReportsProductProblemWithoutChangingStatus(){
        ClubBusinessService s=business(profile());when(db.queryForObject(contains("pr.bound_player_id"),eq(Integer.class),eq(75L))).thenReturn(0);
        assertTrue(assertThrows(ServiceException.class,()->s.updateAvailability(75L,Collections.singletonMap("status","online"))).getMessage().contains("没有可接单商品"));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void missingVoiceReportsOnlyVoice(){
        Map<String,Object> p=profile();p.put("voiceUrl","");ClubBusinessService s=business(p);
        assertEquals("请先补充：语音",assertThrows(ServiceException.class,()->s.updateAvailability(75L,Collections.singletonMap("status","online"))).getMessage());
    }
    @Test void rejectedAdmissionCannotBeBypassed(){
        ClubBusinessService s=business(profile());when(db.queryForObject(contains("club_application"),eq(Integer.class),eq(75L))).thenReturn(0);
        assertTrue(assertThrows(ServiceException.class,()->s.updateAvailability(75L,Collections.singletonMap("status","online"))).getMessage().contains("审核"));
        verify(db,never()).update(anyString(),any(Object[].class));
        assertTrue(ClubPlayerEligibility.approved("p").contains("max(a2.id)"));
        assertTrue(ClubPlayerEligibility.approved("p").contains("i.status<>'approved'"));
    }
    ClubAdminService admin(){return new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));}
    @Test void homepageAndCategoryRolesRejectIndependentMerchants(){
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin(),"roleScope",Collections.singletonMap("role_scope","merchant")));
        verifyNoInteractions(db);
    }
    @Test void homepageRolesPreserveCustomerAndPlayerScopes(){
        for(String role:Arrays.asList("all","user","player")) assertEquals(role,ReflectionTestUtils.invokeMethod(admin(),"roleScope",Collections.singletonMap("role_scope",role)));
    }
    @Test void targetedCreationRequiresRecipientsBeforeAnyWrite(){
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin(),"saveInternal","coupons",Collections.singletonMap("distribution_mode","targeted"),1L,null));verifyNoInteractions(db);
    }
    @Test void deletedCouponIsHiddenWithoutDeletingHistory(){
        admin().delete("coupons",7L,1L,"停止使用","delete-7");
        verify(db).update("update club_coupon set status='deleted' where id=?",7L);
        verify(db,never()).update(startsWith("delete "),any(Object[].class));
        admin().list("coupons","","",1,20);verify(db).queryForObject(contains("club_coupon where status<>'deleted'"),eq(Integer.class),any(Object[].class));
    }
    @Test void activeRechargeRulePreventsCouponDeletion(){
        when(db.queryForObject(contains("club_recharge_coupon_rule"),eq(Integer.class),eq(7L))).thenReturn(1);
        assertThrows(ServiceException.class,()->admin().delete("coupons",7L,1L,"停止使用","delete-7"));
        verify(db,never()).update("update club_coupon set status='deleted' where id=?",7L);
    }
}
