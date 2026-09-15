package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;

class ClubAdminPlayerTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ClubBusinessService business=mock(ClubBusinessService.class);
    final ClubRoleLifecycleService lifecycle=mock(ClubRoleLifecycleService.class);
    final ClubAdminService service=new ClubAdminService(jdbc,business,mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),lifecycle,mock(ClubIdentityCryptoService.class));
    Map<String,Object> input(boolean online){
        when(jdbc.queryForObject(contains("from club_user where id=? and status='active'"),eq(Integer.class),eq(39L))).thenReturn(1);
        when(jdbc.queryForObject(contains("select user_id from club_player_profile"),eq(Long.class),eq(9L))).thenReturn(39L);
        when(jdbc.queryForMap(contains("select user_type,status from club_user"),eq(39L))).thenReturn(new HashMap<String,Object>(){{put("user_type","player");put("status","active");}});
        when(jdbc.queryForMap(contains("select * from club_player_profile where id=?"),eq(9L))).thenReturn(new HashMap<String,Object>(){{put("status","active");put("image","/existing.png");put("intro","existing");put("voice_url","/existing.mp3");}});
        when(jdbc.queryForObject(contains("select service_status"),eq(String.class),eq(39L))).thenReturn("online");
        return new HashMap<String,Object>(){{put("user_id",39L);put("display_name","修改昵称");put("online_status",online);put("status","active");}};
    }
    @Test void existingOnlineProfileSaveDoesNotSwitchRoleOrForceOffline(){
        ReflectionTestUtils.invokeMethod(service,"savePlayer",9L,input(true));
        verifyNoInteractions(lifecycle);
        verify(business).updateAvailability(eq(39L),argThat(m->"online".equals(m.get("status"))));
    }
    @Test void pausedProfileRemainsPausedOnNormalSave(){
        Map<String,Object> input=input(false);
        when(jdbc.queryForObject(contains("select service_status"),eq(String.class),eq(39L))).thenReturn("paused");
        ReflectionTestUtils.invokeMethod(service,"savePlayer",9L,input);
        verify(business).updateAvailability(eq(39L),argThat(m->"paused".equals(m.get("status"))));
    }
    @Test void editingCannotPromoteAnOrdinaryAccount(){
        Map<String,Object> input=input(true);
        when(jdbc.queryForMap(contains("select user_type,status from club_user"),eq(39L))).thenReturn(Collections.singletonMap("user_type","user"));
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"savePlayer",9L,input));
        verifyNoInteractions(lifecycle,business);
    }
    @Test void onlineSaveUsesSharedReadinessValidation(){
        Map<String,Object> input=input(true);
        when(business.updateAvailability(eq(39L),anyMap())).thenThrow(new ServiceException("请先完善陪玩头像"));
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"savePlayer",9L,input));
    }
    @Test void adminCanSetMaleAndFemaleOnAppUser(){
        Map<String,Object> current=new HashMap<>();current.put("user_type","user");current.put("status","active");
        when(jdbc.queryForList(eq("select * from club_user where id=? for update"),eq(39L))).thenReturn(Collections.singletonList(current));
        when(jdbc.queryForMap(eq("select * from club_user where id=?"),eq(39L))).thenReturn(new HashMap<>(current));
        ReflectionTestUtils.invokeMethod(service,"saveUser",39L,Collections.singletonMap("gender","male"));
        ReflectionTestUtils.invokeMethod(service,"saveUser",39L,Collections.singletonMap("gender","女"));
        verify(jdbc).update("update club_user set gender=? where id=?","male",39L);
        verify(jdbc).update("update club_user set gender=? where id=?","female",39L);
    }
}
