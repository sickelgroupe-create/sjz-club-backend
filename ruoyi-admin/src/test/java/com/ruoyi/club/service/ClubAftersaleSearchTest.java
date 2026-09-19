package com.ruoyi.club.service;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ClubAftersaleSearchTest {
    @Test void keywordSearchResolvesOrderNumberFromItsActualTable() {
        JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:aftersaleSearch"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("create table club_order(id bigint,order_no varchar(64))");
        db.execute("create table club_aftersale(id bigint,order_id bigint,aftersale_no varchar(64),reason varchar(64),status varchar(32))");
        db.execute("insert into club_order values(1,'SJZ-order-123')");
        db.execute("insert into club_aftersale values(1,1,'AS-case-456','reason789','applied')");
        ClubAdminService service=new ClubAdminService(db,mock(ClubBusinessService.class),mock(ClubAfterSaleService.class),mock(ClubCatalogService.class),mock(ClubRoleLifecycleService.class),mock(ClubIdentityCryptoService.class));
        for(String word:new String[]{"order-123","case-456","reason789"}) {
            Map<String,Object> result=service.list("aftersales",word,"applied",1,20);
            assertEquals(1,result.get("total"));
            assertEquals(1,((List<?>)result.get("rows")).size());
        }
        assertEquals(0,service.list("aftersales","absent","",1,20).get("total"));
    }
}
