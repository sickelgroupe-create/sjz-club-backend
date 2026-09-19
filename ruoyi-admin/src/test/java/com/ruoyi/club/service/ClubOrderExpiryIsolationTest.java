package com.ruoyi.club.service;

import java.util.UUID;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubOrderExpiryIsolationTest
{
    @Test
    void oneBrokenRemoteTradeCannotRollBackAnotherExpiredOrder()
    {
        Fixture f = new Fixture();
        f.order(1, true);
        f.order(2, false);
        assertEquals(2, f.service().expireUnpaidOrders());
        assertEquals("unpaid", f.status(1));
        assertEquals("cancelled", f.status(2));
        assertEquals(1, f.jdbc.queryForObject("select count(*) from club_order_log", Integer.class));
        assertEquals(2, f.jdbc.queryForObject("select count(*) from club_order where expiry_checked_at is not null", Integer.class));
    }

    @Test
    void twoHundredBrokenTradesCannotStarveTheNextOrderAfterRestart()
    {
        Fixture f = new Fixture();
        for (long id=1; id<=200; id++) f.order(id, true);
        f.order(201, false);
        f.service().expireUnpaidOrders();
        assertEquals("unpaid", f.status(201));
        f.service().expireUnpaidOrders();
        assertEquals("cancelled", f.status(201));
        assertEquals(1, f.jdbc.queryForObject("select count(*) from club_order_log", Integer.class));
    }

    private static final class Fixture
    {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:expiry_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        final ClubWechatPayService wechat = mock(ClubWechatPayService.class);

        Fixture()
        {
            jdbc.execute("create table club_user(id bigint primary key)");
            jdbc.update("insert into club_user values(7)");
            jdbc.execute("create table club_order(id bigint primary key,user_id bigint,status varchar(32),payment_expires_at timestamp,expiry_checked_at timestamp(6),updated_at timestamp,product_id bigint,sku_id bigint,quantity int,cancel_reason varchar(64),cancelled_at timestamp,version int default 0)");
            jdbc.execute("create table club_payment(id bigint primary key,payment_no varchar(32),prepay_id varchar(32),stock_reserved int,order_id bigint,mode varchar(20),status varchar(32))");
            jdbc.execute("create table club_order_log(id bigint auto_increment primary key,order_id bigint,from_status varchar(32),to_status varchar(32),operator_type varchar(20),operator_id bigint,note varchar(500))");
            doThrow(new ServiceException("simulated isolated remote failure")).when(wechat).close(anyString());
            when(wechat.query(anyString())).thenThrow(new ServiceException("simulated isolated remote failure"));
        }

        ClubAppService service()
        {
            ClubAppService app = new ClubAppService(jdbc, mock(ClubAuthService.class), mock(ClubBusinessService.class),
                    mock(ClubCatalogService.class), mock(ClubTeenPolicyService.class), mock(ClubMiniProgramCodeService.class),
                    mock(ClubIdentityCryptoService.class), wechat);
            ReflectionTestUtils.setField(app, "paymentMode", "wechat");
            ReflectionTestUtils.setField(app, "transactionManager", new DataSourceTransactionManager(dataSource));
            return app;
        }

        void order(long id, boolean broken)
        {
            jdbc.update("insert into club_order(id,user_id,status,payment_expires_at,product_id,sku_id,quantity) values(?,7,'unpaid',timestamp '2020-01-01 00:00:00',11,12,1)", id);
            if (broken) jdbc.update("insert into club_payment values(?,?, 'prepay',1,?,'wechat','created')",id,"WX"+id,id);
        }

        String status(long id) { return jdbc.queryForObject("select status from club_order where id=?", String.class, id); }
    }
}
