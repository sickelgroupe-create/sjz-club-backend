package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.DigestUtils;

/** Product and specifications are saved in the caller's single transaction. */
final class ClubProductSpecs {
    private final JdbcTemplate jdbc;
    ClubProductSpecs(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    List<Map<String,Object>> read(Long productId, boolean lock) {
        return jdbc.queryForList("select id,name,price,stock,status from club_product_sku where product_id=? and status<>'deleted' order by id"+(lock?" for update":""),productId);
    }
    static String version(List<Map<String,Object>> rows) {
        // Fixed columns and order, independent of the JDBC map implementation.
        List<List<Object>> values=new ArrayList<>();
        for(Map<String,Object> row:rows) values.add(Arrays.asList(String.valueOf(row.get("id")),row.get("name"),new BigDecimal(String.valueOf(row.get("price"))).stripTrailingZeros().toPlainString(),String.valueOf(row.get("stock")),row.get("status")));
        return DigestUtils.md5DigestAsHex(JSON.toJSONBytes(values));
    }
    List<Map<String,Object>> prepare(Long productId, Map<String,Object> input) {
        Object raw=input.get("skus");
        if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>100) throw new ServiceException("请设置1至100个服务规格");
        List<Map<String,Object>> existing=Collections.emptyList();
        if(productId!=null) {
            List<Map<String,Object>> products=jdbc.queryForList("select id from club_product where id=? and status<>'deleted' for update",productId);
            if(products.isEmpty())throw new ServiceException("商品不存在或已删除");
            existing=read(productId,true);
            if(!version(existing).equals(input.get("sku_version")))throw new ServiceException("商品规格或库存已发生变化，请重新打开商品后编辑");
        }
        Set<Long> expected=new HashSet<>(),seen=new HashSet<>();
        for(Map<String,Object> row:existing)expected.add(Long.valueOf(String.valueOf(row.get("id"))));
        List<Map<String,Object>> result=new ArrayList<>();
        for(Object value:(List<?>)raw) {
            if(!(value instanceof Map))throw new ServiceException("服务规格格式不正确");
            Map<?,?> row=(Map<?,?>)value;
            String name=row.get("name")==null?"":String.valueOf(row.get("name")).trim();
            if(name.isEmpty()||name.length()>100)throw new ServiceException("请填写100字以内的规格名称");
            BigDecimal price; int stock; Long id;
            try { price=new BigDecimal(String.valueOf(row.get("price")));stock=new BigDecimal(String.valueOf(row.get("stock"))).intValueExact();id=row.get("id")==null?null:Long.valueOf(String.valueOf(row.get("id"))); }
            catch(RuntimeException e){throw new ServiceException("规格价格或库存格式不正确");}
            if(price.signum()<=0||price.scale()>2||price.compareTo(new BigDecimal("99999999.99"))>0||stock<0)throw new ServiceException("规格价格必须大于0且最多两位小数，库存必须为非负整数");
            String status=String.valueOf(row.get("status"));
            if(!Arrays.asList("active","inactive").contains(status))throw new ServiceException("规格状态不正确");
            if(id!=null&&(!expected.contains(id)||!seen.add(id)))throw new ServiceException("规格不属于当前商品或重复提交，不能更换规格所属商品");
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);item.put("name",name);item.put("price",price);item.put("stock",stock);item.put("status",status);result.add(item);
        }
        if(!seen.equals(expected))throw new ServiceException("已有规格请使用停用功能，不能直接移除历史规格");
        long total=0;for(Map<String,Object> row:result)if("active".equals(row.get("status")))total+=(Integer)row.get("stock");
        if(total>Integer.MAX_VALUE)throw new ServiceException("商品总库存过大");
        return result;
    }
    void save(Long productId,List<Map<String,Object>> rows) {
        for(Map<String,Object> row:rows) {
            if(row.get("id")==null)jdbc.update("insert into club_product_sku(product_id,name,price,stock,status) values(?,?,?,?,?)",productId,row.get("name"),row.get("price"),row.get("stock"),row.get("status"));
            else jdbc.update("update club_product_sku set name=?,price=?,stock=?,status=? where id=? and product_id=?",row.get("name"),row.get("price"),row.get("stock"),row.get("status"),row.get("id"),productId);
        }
    }
}
