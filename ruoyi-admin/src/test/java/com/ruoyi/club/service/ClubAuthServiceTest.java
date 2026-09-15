package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.ruoyi.club.web.ClubPhoneCodeException;
import com.ruoyi.club.web.ClubUnauthorizedException;

class ClubAuthServiceTest
{
    @Test
    void publicRequestWithoutAuthorizationMayRemainAnonymous()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ClubAuthService service = new ClubAuthService(jdbc, new BCryptPasswordEncoder());
        assertNull(service.optionalUserId(new MockHttpServletRequest()));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void invalidAuthorizationNeverFallsBackToAnonymous()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());
        ClubAuthService service = new ClubAuthService(jdbc, new BCryptPasswordEncoder());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer forged-token");
        assertThrows(ClubUnauthorizedException.class, () -> service.optionalUserId(request));
    }

    @Test
    void phoneRegistrationCannotCreateUserWithoutRegisterVerificationCode()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());
        ClubAuthService service = new ClubAuthService(jdbc, new BCryptPasswordEncoder());
        Map<String, Object> input = new HashMap<>();
        input.put("account", "secure_user");
        input.put("password", "Password123");
        input.put("phone", "13800000000");
        assertThrows(ClubPhoneCodeException.class, () -> service.register(input));
        verify(jdbc, never()).update(eq("insert into club_user(account,phone,email,password_hash,nickname) values(?,?,?,?,?)"), any(Object[].class));
    }

    @Test
    void mergeUsesValueEqualityForLargeIdsAndConservesBothFrozenBalances() throws Exception
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> target = new HashMap<>();
        target.put("user_id", Long.valueOf(200));
        target.put("balance", new BigDecimal("5.00"));
        target.put("frozen", new BigDecimal("5.00"));
        target.put("total_income", new BigDecimal("10.00"));
        target.put("total_withdrawn", new BigDecimal("1.00"));
        Map<String, Object> source = new HashMap<>();
        source.put("user_id", Long.valueOf(201));
        source.put("balance", new BigDecimal("7.00"));
        source.put("frozen", new BigDecimal("3.00"));
        source.put("total_income", new BigDecimal("11.00"));
        source.put("total_withdrawn", new BigDecimal("2.00"));
        when(jdbc.queryForList(eq("select * from club_wallet where user_id in (?,?) for update"), eq(Long.valueOf(200)), eq(Long.valueOf(201))))
                .thenReturn(Arrays.asList(target, source));
        when(jdbc.queryForList(eq("select order_enabled,promotion_enabled,system_enabled from club_notification_setting where user_id=?"), eq(Long.valueOf(201))))
                .thenReturn(Collections.emptyList());

        ClubAuthService service = new ClubAuthService(jdbc, new BCryptPasswordEncoder());
        Method merge = ClubAuthService.class.getDeclaredMethod("mergeBusinessData", Long.class, Long.class, String.class);
        merge.setAccessible(true);
        merge.invoke(service, Long.valueOf(200), Long.valueOf(201), "MRG-LARGE-ID");

        verify(jdbc).update(eq("update club_wallet set balance=balance+?,frozen=frozen+?,total_income=total_income+?,total_withdrawn=total_withdrawn+?,version=version+1 where user_id=?"),
                eq(new BigDecimal("7.00")), eq(new BigDecimal("3.00")), eq(new BigDecimal("11.00")), eq(new BigDecimal("2.00")), eq(Long.valueOf(200)));
        verify(jdbc).update(eq("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)"),
                eq(Long.valueOf(200)), eq("account_merge"), eq(new BigDecimal("7.00")), eq(new BigDecimal("5.00")), eq(new BigDecimal("12.00")), eq(new BigDecimal("8.00")),
                eq("MRG-LARGE-ID"), eq("account_merge"), eq("受控账号合并资金转入"));
    }
}
