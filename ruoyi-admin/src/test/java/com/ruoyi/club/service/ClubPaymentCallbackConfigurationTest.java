package com.ruoyi.club.service;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.ruoyi.club.web.ClubAppController;
import static org.junit.jupiter.api.Assertions.*;

class ClubPaymentCallbackConfigurationTest
{
    @Test void applicationDefaultsAndDeploymentTemplatePointToActualControllerRoutes() throws Exception {
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application.yml"));
        MutablePropertySources sources=new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test",Collections.singletonMap("PUBLIC_API_BASE","https://api.example.com")));
        sources.addLast(new PropertiesPropertySource("application",yaml.getObject()));
        PropertySourcesPropertyResolver resolver=new PropertySourcesPropertyResolver(sources);
        String template=new String(Files.readAllBytes(Paths.get("../deploy/settings.env.example")),StandardCharsets.UTF_8);
        String prefix=ClubAppController.class.getAnnotation(RequestMapping.class).value()[0];
        int found=0;
        for(Method method:ClubAppController.class.getDeclaredMethods()) {
            PostMapping mapping=method.getAnnotation(PostMapping.class);
            if(mapping==null || mapping.value().length==0)continue;
            String route=mapping.value()[0];String property,variable;
            if("/pay/wechat/notify".equals(route)) { property="club.wechat-pay-notify-url";variable="WECHAT_PAY_NOTIFY_URL"; }
            else if("/pay/wechat/refund-notify".equals(route)) { property="club.wechat-pay-refund-notify-url";variable="WECHAT_PAY_REFUND_NOTIFY_URL"; }
            else continue;
            String expected="https://api.example.com"+prefix+route;
            assertEquals(expected,resolver.getProperty(property));
            assertTrue(template.contains(variable+"="+expected));found++;
        }
        assertEquals(2,found);
    }
    @Test void freshSchemaAndUpgradeBothContainRetryColumnsAndIndexes() throws Exception {
        String fresh=new String(Files.readAllBytes(Paths.get("../database/001-schema.sql")),StandardCharsets.UTF_8);
        String upgrade=new String(Files.readAllBytes(Paths.get("../database/migrations/20260919_virtual_payment_reconciliation.sql")),StandardCharsets.UTF_8);
        String virtual=fresh.substring(fresh.indexOf("CREATE TABLE `club_virtual_payment`"),fresh.indexOf("CREATE TABLE `club_wallet`"));
        for(String name:new String[]{"payment_checked_at","refund_checked_at","idx_virtual_payment_check","idx_virtual_refund_check"}) {
            assertTrue(virtual.contains(name),name+" missing from clean install");assertTrue(upgrade.contains(name),name+" missing from upgrade");
        }
        assertFalse(upgrade.toLowerCase().contains("drop table"));
    }
}
