package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.club.web.ClubAppController;
import com.ruoyi.common.exception.ServiceException;

class ClubWithdrawalLookupTest
{
    private JdbcTemplate jdbc;
    private ClubAppService service;

    @BeforeEach void setUp()
    {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:withdrawal-lookup-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table club_withdrawal(id bigint primary key,user_id bigint,withdrawal_no varchar(64)," +
                "amount decimal(12,2),status varchar(32),review_note varchar(255),created_at timestamp,idempotency_key varchar(96))");
        jdbc.update("insert into club_withdrawal values(1,7,'WD-ONE',25.00,'pending',null,current_timestamp,'request-one')");
        jdbc.update("insert into club_withdrawal values(2,8,'WD-TWO',35.00,'approved',null,current_timestamp,'request-other')");
        service = new ClubAppService(jdbc, null, null, null, null, null, null, null);
    }

    @Test void returnsAnExistingIntentWithoutChangingItsStatusOrAmount()
    {
        Map<String, Object> result = service.withdrawalByRequest(7L, "request-one");
        assertEquals(Boolean.TRUE, result.get("found"));
        Map<?, ?> withdrawal = (Map<?, ?>) result.get("withdrawal");
        assertEquals(1L, ((Number) withdrawal.get("id")).longValue());
        assertEquals(new BigDecimal("25.00"), withdrawal.get("amount"));
        assertEquals("pending", withdrawal.get("status"));
        assertEquals(2, jdbc.queryForObject("select count(*) from club_withdrawal", Integer.class));
        assertEquals("pending", jdbc.queryForObject("select status from club_withdrawal where id=1", String.class));
    }

    @Test void anotherUsersKeyDoesNotRevealTheirWithdrawal()
    {
        Map<String, Object> result = service.withdrawalByRequest(7L, "request-other");
        assertEquals(Boolean.FALSE, result.get("found"));
        assertNull(result.get("withdrawal"));
    }

    @Test void aMissingIntentIsExplicitlyUnconfirmedAndCreatesNothing()
    {
        Map<String, Object> result = service.withdrawalByRequest(7L, "not-yet-recorded");
        assertEquals(Boolean.FALSE, result.get("found"));
        assertNull(result.get("withdrawal"));
        assertEquals(2, jdbc.queryForObject("select count(*) from club_withdrawal", Integer.class));
    }

    @Test void emptyAndOversizedKeysAreRejected()
    {
        assertThrows(ServiceException.class, () -> service.withdrawalByRequest(7L, null));
        assertThrows(ServiceException.class, () -> service.withdrawalByRequest(7L, "   "));
        assertThrows(ServiceException.class, () -> service.withdrawalByRequest(7L, String.join("", java.util.Collections.nCopies(97, "a"))));
    }

    @Test void staticRouteUsesAuthenticatedUserAndCannotBeCapturedByTheNumericIdRoute() throws Exception
    {
        ClubAuthService auth = mock(ClubAuthService.class);
        when(auth.requireUserId(any(HttpServletRequest.class))).thenReturn(7L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ClubAppController(auth, service, null, null, null, null)).build();
        mvc.perform(get("/app/withdrawals/by-request").param("key", "request-one").param("userId", "8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.withdrawal").isMap());
        verify(auth).requireUserId(any(HttpServletRequest.class));
    }
}
