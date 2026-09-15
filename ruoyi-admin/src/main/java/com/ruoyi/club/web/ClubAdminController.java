package com.ruoyi.club.web;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.club.service.ClubAdminService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;

@RestController
@RequestMapping("/club/admin")
public class ClubAdminController extends BaseController
{
    private final ClubAdminService service;

    public ClubAdminController(ClubAdminService service)
    {
        this.service = service;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("@clubPermission.canDashboard()")
    public AjaxResult dashboard() { return AjaxResult.success(service.dashboard()); }

    @GetMapping("/{type}/list")
    @PreAuthorize("@clubPermission.canList(#type)")
    public AjaxResult list(@PathVariable String type, @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(service.list(type, keyword, status, pageNum, pageSize));
    }

    @GetMapping("/{type}/{id}")
    @PreAuthorize("@clubPermission.canDetail(#type)")
    public AjaxResult detail(@PathVariable String type, @PathVariable Long id)
    {
        return AjaxResult.success(service.detail(type, id, getUserId()));
    }

    @PostMapping("/{type}")
    @PreAuthorize("@clubPermission.canSave(#type)")
    public AjaxResult save(@PathVariable String type, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.save(type, input, getUserId()));
    }

    @DeleteMapping("/{type}/{id}")
    @PreAuthorize("@clubPermission.canDelete(#type)")
    public AjaxResult delete(@PathVariable String type, @PathVariable Long id,
            @RequestParam String reason, @RequestParam String requestId)
    {
        service.delete(type, id, getUserId(), reason, requestId);
        return AjaxResult.success();
    }
}
