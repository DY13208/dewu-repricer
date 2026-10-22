package com.dewu.repricer.web;

import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * Excel 成本导入。表头（第一行）：sku_id | 采购成本(元) | 其他成本(元) | 店铺ID(可空=全局) | 利润底线(元,可空)
 */
@Service
public class CostImportService {
    private final JdbcTemplate jdbc;
    public CostImportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Result(int imported, List<String> errors) {}

    @Transactional
    public Result importExcel(InputStream in, String batch) throws Exception {
        List<String> errors = new ArrayList<>(); int ok = 0;
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row r = sh.getRow(i); if (r == null) continue;
                String sku = fmt.formatCellValue(r.getCell(0)).trim();
                if (sku.isEmpty()) continue;
                try {
                    long cost = yuanToFen(fmt.formatCellValue(r.getCell(1)));
                    String extraS = fmt.formatCellValue(r.getCell(2)); long extra = extraS.isBlank() ? 0 : yuanToFen(extraS);
                    String shopS = fmt.formatCellValue(r.getCell(3)).trim(); Long shop = shopS.isEmpty() ? null : Long.parseLong(shopS);
                    String mp = fmt.formatCellValue(r.getCell(4)).trim();
                    List<Long> pk = jdbc.queryForList("SELECT id FROM product_skus WHERE sku_id=?", Long.class, sku);
                    if (pk.isEmpty()) { errors.add("第" + (i + 1) + "行: SKU 不存在 " + sku); continue; }
                    if (cost < 0) { errors.add("第" + (i + 1) + "行: 成本不能为负"); continue; }
                    jdbc.update("INSERT INTO sku_costs(shop_id,sku_pk,purchase_cost,extra_cost,import_batch) VALUES (?,?,?,?,?)", shop, pk.get(0), cost, extra, batch);
                    if (!mp.isEmpty()) jdbc.update("UPDATE shop_sku_listings SET min_profit=? WHERE sku_pk=? AND (?::bigint IS NULL OR shop_id=?)", yuanToFen(mp), pk.get(0), shop, shop);
                    ok++;
                } catch (NumberFormatException e) { errors.add("第" + (i + 1) + "行: 数字格式错误"); }
            }
        }
        return new Result(ok, errors);
    }
    public static long yuanToFen(String s) { return new BigDecimal(s.trim().replace(",", "")).movePointRight(2).longValueExact(); }
}
