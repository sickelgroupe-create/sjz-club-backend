package com.ruoyi.club.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.club.web.ClubAppController;
import com.ruoyi.common.exception.ServiceException;

class ClubWalletPaginationTest
{
    private JdbcTemplate jdbc;
    private ClubAppService service;

    @BeforeEach void setUp()
    {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:wallet-page-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table club_wallet_record(id bigint primary key,user_id bigint,record_type varchar(32)," +
                "amount decimal(12,2),balance_before decimal(12,2),balance_after decimal(12,2),frozen_after decimal(12,2)," +
                "reference_no varchar(64),order_id bigint,counterparty_type varchar(24),description varchar(255),created_at timestamp)");
        service = new ClubAppService(jdbc, null, null, null, null, null, null, null);
    }

    private void record(long id, long userId)
    {
        jdbc.update("insert into club_wallet_record values(?,?,'recharge',1,0,1,0,?,null,'test','isolated test',current_timestamp)", id, userId, "TEST-" + id);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("items"); }

    @Test void moreThanOneHundredRowsAreTraversedOnceDespiteConcurrentNewRows()
    {
        for (long id = 1; id <= 137; id++) record(id, 7);
        for (long id = 140; id <= 150; id++) record(id, 8);
        Map<String, Object> page = service.walletRecords(7L, null, 30);
        List<String> all = new ArrayList<>();
        int pages = 0;
        while (true)
        {
            assertEquals("7", page.get("userId"));
            assertTrue(items(page).size() <= 30);
            for (Map<String, Object> item : items(page)) all.add((String) item.get("id"));
            if (++pages == 1) { record(151, 7); record(152, 8); }
            if (!Boolean.TRUE.equals(page.get("hasMore"))) break;
            page = service.walletRecords(7L, (String) page.get("nextBeforeId"), 30);
        }
        assertEquals(5, pages);
        assertEquals(137, all.size());
        assertEquals(137, new HashSet<>(all).size());
        for (int i = 0; i < all.size(); i++) assertEquals(String.valueOf(137 - i), all.get(i));
        assertNull(page.get("nextBeforeId"));
        assertEquals("151", items(service.walletRecords(7L, null, 30)).get(0).get("id"));
    }

    @Test void retryingAnIdenticalCursorReturnsTheSamePageAndNeverChangesData()
    {
        for (long id = 1; id <= 80; id++) record(id, 7);
        String cursor = (String) service.walletRecords(7L, null, 30).get("nextBeforeId");
        Map<String, Object> first = service.walletRecords(7L, cursor, 30);
        record(81, 7);
        assertEquals(first, service.walletRecords(7L, cursor, 30));
        assertEquals(81, jdbc.queryForObject("select count(*) from club_wallet_record", Integer.class));
    }

    @Test void anotherUsersCursorAndForgedUidCannotExposeTheirRecords() throws Exception
    {
        record(10, 7); record(20, 8); record(30, 7);
        Map<String, Object> page = service.walletRecords(7L, "20", 100);
        assertEquals(1, items(page).size());
        assertEquals("10", items(page).get(0).get("id"));
        ClubAuthService auth = mock(ClubAuthService.class);
        when(auth.requireUserId(any(HttpServletRequest.class))).thenReturn(7L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ClubAppController(auth, service, null, null, null, null)).build();
        mvc.perform(get("/app/wallet/records").param("userId", "8").param("beforeId", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value("7"))
                .andExpect(jsonPath("$.data.items[0].id").value("10"));
        verify(auth).requireUserId(any(HttpServletRequest.class));
    }

    @Test void limitsAndCursorsAreStrictlyValidated()
    {
        for (int limit : new int[] { -1, 0, 101, Integer.MAX_VALUE })
            assertThrows(ServiceException.class, () -> service.walletRecords(7L, null, limit));
        for (String cursor : new String[] { "0", "-1", " 1", "1 ", "1.0", "1e3", "01", "9223372036854775808", "1 or 1=1" })
            assertThrows(ServiceException.class, () -> service.walletRecords(7L, cursor, 30));
        assertThrows(ServiceException.class, () -> service.walletRecords(null, null, 30));
        assertThrows(ServiceException.class, () -> service.walletRecords(0L, null, 30));
    }

    @Test void emptyAndExactSizeFinalPagesHaveNoContinuation()
    {
        Map<String, Object> empty = service.walletRecords(7L, null, 30);
        assertTrue(items(empty).isEmpty()); assertEquals(false, empty.get("hasMore")); assertNull(empty.get("nextBeforeId"));
        for (long id = 1; id <= 30; id++) record(id, 7);
        Map<String, Object> fullFinal = service.walletRecords(7L, null, 30);
        assertEquals(30, items(fullFinal).size()); assertEquals(false, fullFinal.get("hasMore"));
        Map<String, Object> end = service.walletRecords(7L, "1", 30);
        assertTrue(items(end).isEmpty()); assertEquals(false, end.get("hasMore"));
    }

    @Test void bigintIdsAreLosslessStrings()
    {
        record(9007199254740993L, 7); record(9007199254740994L, 7);
        Map<String, Object> page = service.walletRecords(7L, null, 1);
        assertEquals("9007199254740994", items(page).get(0).get("id"));
        assertEquals("9007199254740994", page.get("nextBeforeId"));
        assertEquals("9007199254740993", items(service.walletRecords(7L, (String) page.get("nextBeforeId"), 1)).get(0).get("id"));
    }
}
