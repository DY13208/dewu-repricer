package com.dewu.repricer.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class AuditService {
    private final JdbcTemplate jdbc; private final ObjectMapper om = new ObjectMapper();
    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void log(String actor, String action, Long shopId, String type, String id, Map<String, ?> detail) {
        try {
            jdbc.update("INSERT INTO audit_logs(actor,action,shop_id,target_type,target_id,detail) VALUES (?,?,?,?,?,?::jsonb)",
                    actor == null ? "system" : actor, action, shopId, type, id, om.writeValueAsString(detail));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
