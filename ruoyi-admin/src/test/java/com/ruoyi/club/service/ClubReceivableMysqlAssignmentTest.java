package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * H2's MySQL mode does not implement MySQL's left-to-right SET assignment rule.
 * Execute the actual service SQL one assignment at a time after selecting the
 * original matching rows, reproducing MySQL's semantics without any network DB.
 * https://dev.mysql.com/doc/refman/8.4/en/update.html
 */
class ClubReceivableMysqlAssignmentTest
{
    @Test
    void automaticRecoveryKeepsTheUnpaidRemainderCollectible()
    {
        Fixture f = new Fixture();
        f.release("50.00");
        f.assertDebt("50.00", "outstanding");
        f.release("25.00");
        f.assertDebt("75.00", "outstanding");
        f.release("25.00");
        f.assertDebt("100.00", "recovered");
        assertEquals(Arrays.asList(money("0.00"), money("0.00"), money("0.00")), f.releasedBalances);
    }

    @Test
    void manualHalfWriteoffDoesNotHideTheOtherHalf()
    {
        Fixture f = new Fixture();
        f.writeoff("50.00");
        f.assertDebt("50.00", "outstanding");
        f.writeoff("50.00");
        f.assertDebt("100.00", "recovered");
    }

    @Test
    void manualFullWriteoffMarksTheDebtRecovered()
    {
        Fixture f = new Fixture();
        f.writeoff("100.00");
        f.assertDebt("100.00", "recovered");
    }

    @Test
    void fractionalCentWriteoffIsRejectedWithoutChangingTheDebt()
    {
        Fixture f = new Fixture();
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> f.writeoff("0.005"));
        f.assertDebt("0.00", "outstanding");
    }

    @Test
    void manualWriteoffRetryCannotForgiveTheDebtTwice()
    {
        Fixture f = new Fixture();
        Map<String,Object> input = new java.util.LinkedHashMap<>();
        input.put("id", 3L); input.put("amount", "20.00"); input.put("reason", "核销测试"); input.put("requestId", "writeoff-1");
        f.admin.save("receivables", input, 1L);
        f.admin.save("receivables", input, 1L);
        f.assertDebt("20.00", "outstanding");
        input.put("amount", "30.00");
        assertThrows(com.ruoyi.club.web.ClubConflictException.class, () -> f.admin.save("receivables", input, 1L));
        f.assertDebt("20.00", "outstanding");
    }

    @Test
    void unresolvedAftersaleDoesNotReleaseProtectedIncome()
    {
        Fixture f = new Fixture();
        when(f.jdbc.queryForList(contains("select status from club_order where id=(select order_id"), eq(8L)))
                .thenReturn(Collections.singletonList(Collections.singletonMap("status", "refunding")));
        f.release("100.00");
        f.assertDebt("0.00", "outstanding");
        assertTrue(f.releasedBalances.isEmpty());
        verify(f.jdbc, never()).queryForList(startsWith("select * from club_order_settlement where id=?"), eq(8L));
    }

    @Test
    void pendingDisputesCannotStarveLaterEligibleSettlements()
    {
        Fixture f = new Fixture();
        f.sql.execute("create table club_order(id bigint primary key,status varchar(32))");
        f.sql.execute("create table club_order_settlement(id bigint primary key,order_id bigint,status varchar(32),available_at timestamp)");
        for (long id = 1; id <= 101; id++)
        {
            f.sql.update("insert into club_order values(?,'refunding')", id);
            f.sql.update("insert into club_order_settlement values(?,?,'protected',current_timestamp)", id, id);
        }
        f.sql.update("insert into club_order values(102,'completed')");
        f.sql.update("insert into club_order_settlement values(102,102,'protected',current_timestamp)");
        ClubBusinessService service = new ClubBusinessService(f.sql, mock(ClubCatalogService.class));
        assertEquals(Collections.singletonList(102L), service.readyProtectedSettlements());
    }

    @Test
    void automaticRecoveryOnlyReleasesIncomeExceedingTheRemainingDebt()
    {
        Fixture f = new Fixture();
        f.writeoff("40.00");
        f.release("80.00");
        f.assertDebt("100.00", "recovered");
        assertEquals(Collections.singletonList(money("20.00")), f.releasedBalances);
    }

    @Test
    void sequentialSqlHarnessReproducesTheOriginalMysqlFailure()
    {
        Fixture f = new Fixture();
        f.executeMysqlUpdate("update club_provider_receivable set recovered_amount=recovered_amount+?,status=case when recovered_amount+?>=amount then 'recovered' else 'outstanding' end,updated_at=now() where id=?",
                money("50.00"), money("50.00"), 3L);
        f.assertDebt("50.00", "recovered");
        assertTrue(f.sql.queryForList("select id from club_provider_receivable where status='outstanding' and recovered_amount<amount").isEmpty());
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static final class Fixture
    {
        final JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:receivable_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final ClubBusinessService business = new ClubBusinessService(jdbc, mock(ClubCatalogService.class));
        final ClubAdminService admin = new ClubAdminService(jdbc, business, mock(ClubAfterSaleService.class),
                mock(ClubCatalogService.class), mock(ClubRoleLifecycleService.class), mock(ClubIdentityCryptoService.class));
        final List<BigDecimal> releasedBalances = new ArrayList<>();
        final Map<String,Object> action = new HashMap<>();

        Fixture()
        {
            sql.execute("create table club_provider_receivable(id bigint primary key,provider_user_id bigint,order_id bigint,aftersale_id bigint,amount decimal(12,2),recovered_amount decimal(12,2),status varchar(32),updated_at timestamp)");
            sql.update("insert into club_provider_receivable values(3,4,12,9,100,0,'outstanding',current_timestamp)");
            when(jdbc.queryForList(contains("select status from club_order where id=(select order_id"), eq(8L)))
                    .thenReturn(Collections.singletonList(Collections.singletonMap("status", "completed")));
            when(jdbc.queryForList(contains("from club_provider_receivable where provider_user_id=?"), eq(4L)))
                    .thenAnswer(call -> sql.queryForList(call.getArgument(0), 4L));
            when(jdbc.queryForList(contains("from club_provider_receivable where id=?"), eq(3L)))
                    .thenAnswer(call -> sql.queryForList(call.getArgument(0), 3L));
            when(jdbc.update(startsWith("update club_provider_receivable set"), any(), any(), any(), any()))
                    .thenAnswer(call -> executeMysqlUpdate(call.getArgument(0), Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length)));
            when(jdbc.update(startsWith("update club_provider_receivable set"), any(), any(), any()))
                    .thenAnswer(call -> executeMysqlUpdate(call.getArgument(0), Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length)));
            when(jdbc.update(startsWith("update club_wallet set balance=?"), any(), any(), eq(4L)))
                    .thenAnswer(call -> { releasedBalances.add(call.getArgument(1)); return 1; });
            when(jdbc.update(startsWith("insert into club_admin_action_request"), eq(1L), eq("receivables"), eq("writeoff-1"), anyString()))
                    .thenAnswer(call -> { action.putIfAbsent("payload_hash", call.getArgument(4)); return 1; });
            when(jdbc.queryForMap(startsWith("select payload_hash,result_json from club_admin_action_request"), eq(1L), eq("receivables"), eq("writeoff-1")))
                    .thenAnswer(call -> new HashMap<>(action));
            when(jdbc.update(startsWith("update club_admin_action_request set result_json"), anyString(), eq(1L), eq("receivables"), eq("writeoff-1"), anyString()))
                    .thenAnswer(call -> { action.put("result_json", call.getArgument(1)); return 1; });
            wallet(money("100.00"));
        }

        void wallet(BigDecimal frozen)
        {
            Map<String,Object> wallet = new HashMap<>();
            wallet.put("balance", money("0.00"));
            wallet.put("frozen", frozen);
            when(jdbc.queryForMap(contains("from club_wallet where user_id=?"), eq(4L))).thenReturn(wallet);
        }

        void release(String value)
        {
            BigDecimal income = money(value);
            Map<String,Object> settlement = new HashMap<>();
            settlement.put("id", 8L);
            settlement.put("provider_user_id", 4L);
            settlement.put("provider_income", income);
            settlement.put("reversed_income", money("0.00"));
            settlement.put("settlement_no", "ST8");
            settlement.put("order_id", 12L);
            settlement.put("provider_type", "player");
            when(jdbc.queryForList(startsWith("select * from club_order_settlement where id=?"), eq(8L)))
                    .thenReturn(Collections.singletonList(settlement));
            wallet(income);
            business.releaseProtectedSettlement(8L);
        }

        void writeoff(String value)
        {
            Map<String,Object> input = new HashMap<>();
            input.put("amount", value);
            input.put("reason", "本地回归测试核销");
            ReflectionTestUtils.invokeMethod(admin, "writeoffReceivable", 3L, input, 1L);
        }

        void assertDebt(String recovered, String status)
        {
            Map<String,Object> row = sql.queryForMap("select recovered_amount,status from club_provider_receivable where id=3");
            assertEquals(money(recovered), row.get("recovered_amount"));
            assertEquals(status, row.get("status"));
        }

        int executeMysqlUpdate(String statement, Object... args)
        {
            String prefix = "update club_provider_receivable set ";
            assertTrue(statement.startsWith(prefix));
            int where = statement.indexOf(" where ");
            String assignments = statement.substring(prefix.length(), where);
            int setParameters = (int) assignments.chars().filter(c -> c == '?').count();
            List<Long> ids = sql.queryForList("select id from club_provider_receivable" + statement.substring(where),
                    Long.class, Arrays.copyOfRange(args, setParameters, args.length));
            int offset = 0;
            for (String assignment : assignments.split(","))
            {
                int count = (int) assignment.chars().filter(c -> c == '?').count();
                for (Long id : ids)
                {
                    Object[] values = Arrays.copyOfRange(args, offset, offset + count + 1);
                    values[count] = id;
                    sql.update(prefix + assignment + " where id=?", values);
                }
                offset += count;
            }
            return ids.size();
        }
    }
}
