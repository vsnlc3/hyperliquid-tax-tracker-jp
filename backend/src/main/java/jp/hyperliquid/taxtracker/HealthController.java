package jp.hyperliquid.taxtracker;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final DatabaseHealth databaseHealth;

    public HealthController(DatabaseHealth databaseHealth) {
        this.databaseHealth = databaseHealth;
    }

    @GetMapping("/health")
    public ResponseEntity<DatabaseHealth.HealthResult> health() {
        DatabaseHealth.HealthResult result = databaseHealth.check();
        HttpStatus status = "UP".equals(result.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(result);
    }
}
