package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;

class ClubProductionAuthTest {
    @Test void historicalSmsSwitchCannotReenableCodeIssuanceOrLogin(){
        ReflectionTestUtils.setField(service,"phoneAuthMode","mock");
        assertThrows(ServiceException.class,()->service.phoneCode(Collections.emptyMap()));
        assertThrows(ServiceException.class,()->service.phoneCodeLogin(Collections.emptyMap()));
        verifyNoInteractions(jdbc);
    }
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    final ClubAuthService service=new ClubAuthService(jdbc,encoder);
    final Map<String,Object> user=new HashMap<>();
    void userView(){
        user.put("id",39L);user.put("status","active");
        when(jdbc.queryForList(startsWith("select id,account,phone,email,nickname"),eq(39L))).thenReturn(Collections.singletonList(user));
    }
    @Test void verifiedRegistrationCanThenLoginUsingSamePassword() throws Exception {
        ReflectionTestUtils.setField(service,"phoneAuthMode","disabled");userView();
        ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);ReflectionTestUtils.setField(service,"wechatPhone",phone);when(phone.verifiedPhone("verified-code")).thenReturn("13800000000");
        Connection conn=mock(Connection.class);PreparedStatement statement=mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString(),eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        doAnswer(call->{user.put("password_hash",call.getArgument(1));return null;}).when(statement).setObject(eq(3),any());
        when(jdbc.update(any(PreparedStatementCreator.class),any(KeyHolder.class))).thenAnswer(call->{
            call.<PreparedStatementCreator>getArgument(0).createPreparedStatement(conn);
            call.<KeyHolder>getArgument(1).getKeyList().add(Collections.singletonMap("id",39L));return 1;
        });
        Map<String,Object> registered=service.phoneRegister(new HashMap<String,Object>(){{put("phone","13800000000");put("password","Password123");put("phoneCode","verified-code");}});
        assertNotNull(registered.get("accessToken"));assertTrue(encoder.matches("Password123",String.valueOf(user.get("password_hash"))));
        assertEquals(false,registered.get("wechatBindingRequired"));
        verify(jdbc).update(contains("insert into club_user_token"),eq(39L),eq("access"),anyString(),any(Timestamp.class));
        when(jdbc.queryForList(startsWith("select * from club_user where account=? or phone=?"),eq("13800000000"),eq("13800000000"),eq("13800000000"))).thenReturn(Collections.singletonList(user));
        assertNotNull(service.login(new HashMap<String,Object>(){{put("account","13800000000");put("password","Password123");}}).get("accessToken"));
        verify(jdbc,never()).queryForList(contains("club_phone_code"),any(Object[].class));
    }
    @Test void registrationCannotOverwriteExistingPhoneAccount(){
        ClubWechatPhoneService phone=mock(ClubWechatPhoneService.class);ReflectionTestUtils.setField(service,"wechatPhone",phone);when(phone.verifiedPhone("verified-code")).thenReturn("13800000000");
        when(jdbc.queryForList(contains("where phone=? limit 1"),eq("13800000000"))).thenReturn(Collections.singletonList(Collections.singletonMap("id",39L)));
        assertThrows(ServiceException.class,()->service.phoneRegister(new HashMap<String,Object>(){{put("phone","13800000000");put("password","Password123");}}));
        verify(jdbc,never()).update(any(PreparedStatementCreator.class),any(KeyHolder.class));
    }
    @Test void resetRequiresOldPasswordOrBoundWechatProof(){
        when(jdbc.queryForObject(startsWith("select password_hash"),eq(String.class),eq(39L))).thenReturn(encoder.encode("OldPass123"));
        assertThrows(ServiceException.class,()->service.resetPassword(39L,Collections.singletonMap("password","NewPass123")));
        verify(jdbc,never()).update(startsWith("update club_user set password_hash"),any(Object[].class));
    }
    @Test void verifiedResetReplacesHashAndRevokesPreviousSessions(){
        userView();when(jdbc.queryForObject(startsWith("select password_hash"),eq(String.class),eq(39L))).thenReturn(encoder.encode("OldPass123"));
        assertNotNull(service.resetPassword(39L,new HashMap<String,Object>(){{put("password","NewPass123");put("oldPassword","OldPass123");}}).get("accessToken"));
        verify(jdbc).update(eq("update club_user set password_hash=? where id=?"),argThat((String hash)->encoder.matches("NewPass123",hash)),eq(39L));
        verify(jdbc,atLeastOnce()).update(eq("update club_user_token set revoked_at=now() where user_id=? and revoked_at is null"),eq(39L));
    }
    @Test void genderContractIsSharedAndRejectsUnexpectedValues(){
        assertEquals("male",ClubGender.normalize("男"));assertEquals("female",ClubGender.normalize("female"));
        assertEquals("unknown",ClubGender.normalize(null));assertThrows(ServiceException.class,()->ClubGender.normalize("invalid"));
    }
}
