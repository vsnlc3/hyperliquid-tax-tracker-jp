package jp.hyperliquid.taxtracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseHealthTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DatabaseHealth databaseHealth;

    @Test
    void reportsDatabaseAsUpWhenProbeSucceeds() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertThat(databaseHealth.check())
                .extracting(DatabaseHealth.HealthResult::status, DatabaseHealth.HealthResult::database)
                .containsExactly("UP", "UP");
    }

    @Test
    void reportsDatabaseAsDownWhenProbeFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(databaseHealth.check())
                .extracting(DatabaseHealth.HealthResult::status, DatabaseHealth.HealthResult::database)
                .containsExactly("DOWN", "DOWN");
    }
}
