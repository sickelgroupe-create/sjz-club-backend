package com.ruoyi.club.service;
import java.util.*;

final class ClubPlayerReadiness {
    private ClubPlayerReadiness() { }
    static boolean yes(Object value) { return Boolean.TRUE.equals(value)||"1".equals(String.valueOf(value)); }
    static String describe(Map<String,Object> row) {
        List<String> missing=new ArrayList<>();
        if(!"active".equals(row.get("status")))missing.add("打手已停用");
        if(!yes(row.get("admission_ready")))missing.add("入驻审核未通过或账号停用");
        for(String[] field:new String[][]{{"display_name","昵称"},{"image","头像"},{"intro","简介"},{"voice_url","语音"}})
            if(row.get(field[0])==null||String.valueOf(row.get(field[0])).trim().isEmpty())missing.add("缺少"+field[1]);
        if(!yes(row.get("has_product")))missing.add("未绑定上架商品");
        else if(!yes(row.get("has_sku")))missing.add("商品没有启用规格");
        else if(!yes(row.get("has_stock")))missing.add("商品规格库存不足");
        if("busy".equals(row.get("service_status")))missing.add("接单中，暂时无法服务");
        else if(!yes(row.get("online_status"))||!"online".equals(row.get("service_status")))missing.add("尚未开启在线接单");
        String gender=String.valueOf(row.get("gender"));
        String category="male".equals(gender)?"男生分类":"female".equals(gender)?"女生分类":"未设置性别，仅进入全部分类";
        return missing.isEmpty()?"可接单 · "+category:String.join("；",missing);
    }
}
