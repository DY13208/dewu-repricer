package com.dewu.repricer.adapter.dewu;

import com.dewu.repricer.adapter.AdapterException;
import com.dewu.repricer.adapter.PlatformAdapter;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 得物开放平台（open.dewu.com）真实适配器 —— 骨架，尚未接入。
 * 接入前提：完成开发者入驻 + 应用审核 + 商家授权，并在开放平台控制台获得对应 API 权限包；
 * 接口路径、签名算法、字段一律以控制台文档为准，不得使用 App/商家后台私有接口、抓包签名或模拟客户端。
 * 所有方法当前抛出 NOT_IMPLEMENTED，任务执行器会将其记为不可重试失败，不会误改价。
 */
@Component
public class DewuOpenPlatformAdapter implements PlatformAdapter {
    private final DewuOpenProperties props;
    public DewuOpenPlatformAdapter(DewuOpenProperties props) { this.props = props; }

    @Override public String type() { return "DEWU_OPEN"; }
    /** 能力声明保守为 false，待逐项核实官方权限后再打开。 */
    @Override public Capabilities capabilities() { return new Capabilities(false, false, false, false, false, false, false); }

    private AdapterException todo(String op) {
        return new AdapterException("NOT_IMPLEMENTED", "得物官方接口未接入: " + op + (props.configured() ? "" : "（凭据未配置）"), false);
    }
    @Override public AuthResult authorizeShop(ShopContext s, String code) { throw todo("authorizeShop / OAuth2 换取 token"); }
    @Override public Page<ProductDto> listProducts(ShopContext s, int p, int n) { throw todo("listProducts"); }
    @Override public Page<ListingDto> listSkuListings(ShopContext s, int p, int n) { throw todo("listSkuListings / 出价列表"); }
    @Override public Optional<Long> getCurrentPrice(ShopContext s, String id) { throw todo("getCurrentPrice"); }
    @Override public Optional<CompetitorPrice> getCompetitorPrice(ShopContext s, String sku) { return Optional.empty(); }
    @Override public UpdateResult updateListingPrice(ShopContext s, String id, long price, String idem) { throw todo("updateListingPrice / 修改出价"); }
    @Override public boolean enablePlatformAutoFollow(ShopContext s, String id, boolean e) { return false; }
    @Override public UpdateResult getUpdateResult(ShopContext s, String rid) { throw todo("getUpdateResult"); }
}
