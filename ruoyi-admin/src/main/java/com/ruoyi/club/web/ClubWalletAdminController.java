package com.ruoyi.club.web;
import java.util.Map;
import com.ruoyi.club.service.ClubWalletAdminService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/club/admin/wallets")
public class ClubWalletAdminController extends BaseController {
    private final ClubWalletAdminService service;
    public ClubWalletAdminController(ClubWalletAdminService service){this.service=service;}
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('club:wallet:list')")
    public AjaxResult list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(service.list(keyword,pageNum,pageSize));}
    @GetMapping("/{userId}") @PreAuthorize("@ss.hasPermi('club:wallet:list')")
    public AjaxResult detail(@PathVariable Long userId){return AjaxResult.success(service.detail(userId));}
    @GetMapping("/{userId}/records") @PreAuthorize("@ss.hasPermi('club:wallet:list')")
    public AjaxResult records(@PathVariable Long userId,@RequestParam(defaultValue="") String startDate,@RequestParam(defaultValue="") String endDate,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(service.records(userId,startDate,endDate,pageNum,pageSize));}
    @PostMapping("/{userId}/adjustments") @PreAuthorize("@ss.hasPermi('club:wallet:adjust')")
    public AjaxResult adjust(@PathVariable Long userId,@RequestBody Map<String,Object> input){return AjaxResult.success(service.adjust(userId,getUserId(),input));}
}
