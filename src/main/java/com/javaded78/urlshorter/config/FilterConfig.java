package com.javaded78.urlshorter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Explicit servlet filter registration for the two request-bounding filters.
 *
 * <p>Both filters are {@code @Component}s, so without a bean the container would wire them in
 * Spring scan order — non-deterministic and undocumented. Registering them here pins the logical
 * order with an explicit contract:
 *
 * <ol>
 *   <li>{@link RequestUriLengthFilter} runs {@link Order#HIGHEST_PRECEDENCE} — a crafted path is
 *       rejected before routing or body parsing touches the servlet container.
 *   <li>{@link RequestSizeLimitFilter} runs {@link Order#LOWEST_PRECEDENCE} — after routing, so a
 *       legitimate request is never blocked by the URI check, and the body is bounded as late as
 *       possible.
 * </ol>
 *
 * <p>{@link FilterRegistrationBean} (over a plain {@link
 * org.springframework.boot.web.servlet.FilterRegistrationAutoConfiguration} component) also lets us
 * expose the URL patterns and the order, both of which stay stable as more filters are added later.
 */
@Configuration
@RequiredArgsConstructor
public class FilterConfig {

  private final ShortenerProperties properties;

  @Bean
  public FilterRegistrationBean<RequestUriLengthFilter> requestUriLengthFilter() {
    var registration = new FilterRegistrationBean<>(new RequestUriLengthFilter(properties));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    registration.setName("requestUriLengthFilter");
    return registration;
  }

  @Bean
  public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
    var registration = new FilterRegistrationBean<>(new RequestSizeLimitFilter(properties));
    registration.setOrder(Ordered.LOWEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    registration.setName("requestSizeLimitFilter");
    return registration;
  }
}
