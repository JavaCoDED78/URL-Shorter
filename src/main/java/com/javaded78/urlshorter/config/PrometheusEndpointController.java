package com.javaded78.urlshorter.config;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manually exposes /actuator/prometheus in exposition format.
 *
 * micrometer-registry-prometheus 1.17.x with Spring Boot 4.1.1 does not
 * auto-configure /actuator/prometheus because the AutoConfiguration.imports
 * file is missing. This controller serves the Prometheus scrape output
 * from PrometheusMeterRegistry.scrape().
 */
@RestController
@Order(1)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PrometheusEndpointController {

    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public PrometheusEndpointController(PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String scrape() {
        return prometheusMeterRegistry.scrape();
    }
}
