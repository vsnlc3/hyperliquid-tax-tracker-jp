package jp.hyperliquid.taxtracker;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseHealth {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealth(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HealthResult check() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (Integer.valueOf(1).equals(result)) {
                return new HealthResult("UP", "UP");
            }
            return new HealthResult("DOWN", "DOWN");
        } catch (DataAccessException exception) {
            return new HealthResult("DOWN", "DOWN");
        }
    }

    public record HealthResult(String status, String database) {
    }
}
