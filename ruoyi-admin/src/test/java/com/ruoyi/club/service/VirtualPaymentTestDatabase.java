package com.ruoyi.club.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated SQL fixture: never loads a developer's or production database settings. */
final class VirtualPaymentTestDatabase
{
    final DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(source));

    VirtualPaymentTestDatabase()
    {
        String[] ddl={
            "club_payment(id bigint primary key,order_id bigint,user_id bigint,payment_no varchar(32) unique,mode varchar(20),amount decimal(12,2),status varchar(32),refund_no varchar(64),refund_status varchar(20),wechat_refund_id varchar(128),mock_transaction_no varchar(128),paid_at timestamp,refunded_amount decimal(12,2),refunded_at timestamp)",
            "club_virtual_payment(payment_no varchar(32) primary key,environment tinyint,openid varchar(128),expected_fen int,delivery_confirmed tinyint,order_type int,refund_no varchar(32) unique,remote_status int,remote_order_id varchar(128),updated_at timestamp default current_timestamp,payment_checked_at datetime(6),refund_checked_at datetime(6))",
            "club_order(id bigint primary key,order_no varchar(48),user_id bigint,provider_user_id bigint,provider_type varchar(20),total_amount decimal(12,2),status varchar(32),version int default 0,quantity int,sku_id bigint,product_id bigint,platform_fee decimal(12,2),provider_income decimal(12,2),refunded_at timestamp)",
            "club_aftersale(id bigint auto_increment primary key,aftersale_no varchar(48) unique,order_id bigint unique,user_id bigint,provider_user_id bigint,status varchar(32),reason varchar(64),description varchar(1000),evidence_json text,refund_amount decimal(12,2),request_id varchar(96),refund_idempotency_key varchar(96),reviewed_by bigint,review_note varchar(1000),refunded_at timestamp)",
            "club_aftersale_log(id bigint auto_increment primary key,aftersale_id bigint,from_status varchar(32),to_status varchar(32),operator_type varchar(20),operator_id bigint,note varchar(1000))",
            "club_aftersale_admin_request(request_id varchar(96) primary key,aftersale_id bigint not null,admin_user_id bigint,action varchar(16) not null,payload_hash char(64) not null,before_json clob not null,result_json clob,created_at timestamp default current_timestamp,completed_at timestamp)",
            "club_payment_audit(id bigint auto_increment primary key,payment_id bigint,order_id bigint,user_id bigint,action varchar(32),result_status varchar(20),request_id varchar(80),detail varchar(500))",
            "club_order_log(id bigint auto_increment primary key,order_id bigint,from_status varchar(32),to_status varchar(32),operator_type varchar(20),operator_id bigint,note varchar(1000))",
            "club_product_sku(id bigint primary key,stock int)",
            "club_product(id bigint primary key,sales int)",
            "club_order_settlement(id bigint primary key,order_id bigint,settlement_no varchar(48),provider_type varchar(20),provider_user_id bigint,status varchar(32),provider_income decimal(12,2),reversed_income decimal(12,2),refunded_amount decimal(12,2),reversed_at timestamp)",
            "club_wallet(user_id bigint primary key,balance decimal(12,2),frozen decimal(12,2),total_income decimal(12,2),version int default 0)",
            "club_wallet_record(id bigint auto_increment primary key,user_id bigint,record_type varchar(32),amount decimal(12,2),balance_before decimal(12,2),balance_after decimal(12,2),frozen_after decimal(12,2),reference_no varchar(80),order_id bigint,counterparty_type varchar(20),description varchar(500))",
            "club_provider_receivable(id bigint auto_increment primary key,receivable_no varchar(48),provider_user_id bigint,order_id bigint,aftersale_id bigint,amount decimal(12,2),status varchar(32))",
            "club_withdrawal(id bigint primary key,user_id bigint,withdrawal_no varchar(48),amount decimal(12,2),status varchar(32))",
            "club_teen_daily_spend(user_id bigint,spend_date date,used_amount decimal(12,2),updated_at timestamp)",
            "club_order_coupon(order_id bigint,status varchar(32))",
            "club_experience_record(order_id bigint,user_id bigint,delta int,event_type varchar(32))",
            "club_message(id bigint auto_increment primary key,user_id bigint,message_type varchar(20),title varchar(100),content varchar(1000),reference_type varchar(20),reference_id bigint)"
        };
        for(String table:ddl)jdbc.execute("create table "+table);
    }

    void payment(int id,String status,Integer channel)
    {
        String no=String.format("VP%08d",id);
        jdbc.update("insert into club_payment(id,order_id,user_id,payment_no,mode,amount,status,mock_transaction_no,paid_at) values(?,?,7,?,'wechat',99,?,?,current_timestamp)",id,id,no,status,"wx-"+id);
        jdbc.update("insert into club_virtual_payment(payment_no,environment,openid,expected_fen,delivery_confirmed,order_type) values(?,0,'openid-7',9900,0,?)",no,channel);
    }

    void appleOrder()
    {
        payment(1,"success",7);
        jdbc.update("insert into club_order(id,order_no,user_id,provider_user_id,provider_type,total_amount,status,quantity,sku_id,product_id,platform_fee,provider_income) values(1,'O1',7,9,'player',99,'completed',1,11,12,19,80)");
        jdbc.update("insert into club_product_sku values(11,10)");
        jdbc.update("insert into club_product values(12,1)");
        jdbc.update("insert into club_order_settlement values(1,1,'ST1','player',9,'settled',80,0,0,null)");
        jdbc.update("insert into club_wallet(user_id,balance,frozen,total_income) values(9,30,0,80)");
    }
}
