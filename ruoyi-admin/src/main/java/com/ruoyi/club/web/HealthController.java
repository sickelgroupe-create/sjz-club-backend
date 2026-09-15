package com.ruoyi.club.web;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;

@RestController
public class HealthController
{
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public AjaxResult health()
    {
        Integer database = jdbc.queryForObject("select 1", Integer.class);
        Map<String, Object> result = new HashMap<>();
        result.put("status", database != null && database == 1 ? "UP" : "DOWN");
        result.put("database", database != null && database == 1 ? "UP" : "DOWN");
        return AjaxResult.success(result);
    }
}
