package com.javaded78.urlshorter.infra.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Connection pool review (Task 14.3): virtual threads increase request concurrency, but the
 * PostgreSQL pool must remain the explicit, bounded ceiling for database access. The value is
 * pinned in application.yaml and asserted here so a future bump requires a conscious, tested
 * decision.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConnectionPoolIT {

  @Autowired private DataSource dataSource;

  @Test
  void postgresPoolHasExplicitBoundedSize() {
    assertThat(dataSource).isInstanceOf(HikariDataSource.class);
    HikariDataSource hikari = (HikariDataSource) dataSource;

    assertThat(hikari.getMaximumPoolSize())
        .as(
            "PostgreSQL pool must stay bounded — virtual threads must not "
                + "be used to hide connection pool pressure")
        .isEqualTo(10);
  }
}
