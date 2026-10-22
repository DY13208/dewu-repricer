package com.dewu.repricer.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

/**
 * 店铺权限隔离：请求头 X-User 标识操作人（生产需接入 SSO/JWT 网关）。ADMIN 或未配置用户时可见全部店铺；
 * 否则仅可见 user_shop_permissions 中授权的店铺。所有店铺维度查询必须通过 allowedShopIds 过滤。
 */
@Component
public class ShopScope {
    private final JdbcTemplate jdbc;
    public ShopScope(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Long[] allowedShopIds(String user) {
        List<String> roles = user == null ? List.of() : jdbc.queryForList("SELECT role FROM app_users WHERE username=?", String.class, user);
        Integer users = jdbc.queryForObject("SELECT count(*) FROM app_users", Integer.class);
        if ((users != null && users == 0) || roles.contains("ADMIN"))
            return jdbc.queryForList("SELECT id FROM shops", Long.class).toArray(new Long[0]);
        return jdbc.queryForList("SELECT p.shop_id FROM user_shop_permissions p JOIN app_users u ON u.id=p.user_id WHERE u.username=?", Long.class, user).toArray(new Long[0]);
    }
    public void check(String user, long shopId) {
        for (Long id : allowedShopIds(user)) if (id == shopId) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无该店铺权限");
    }
}
