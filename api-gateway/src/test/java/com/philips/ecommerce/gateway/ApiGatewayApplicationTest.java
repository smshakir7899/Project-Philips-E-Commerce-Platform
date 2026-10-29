package com.philips.ecommerce.gateway;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load test that imports the REAL config-repo/api-gateway.yml (instead of the static test routes), so a
 * typo in a route, predicate or filter argument in the shared config repository fails the build here and not at
 * deployment time. lb:// URIs do not need Eureka until a request is actually routed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=file:../config-repo/api-gateway.yml")
class ApiGatewayApplicationTest {

    private static final String[] SERVICES = {"product-service", "inventory-service", "cart-service",
            "order-service", "payment-service", "notification-service"};

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private RetryGatewayFilterFactory retryFactory;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TimeLimiterRegistry timeLimiterRegistry;

    @Autowired
    private ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Test
    void allRoutesFromConfigRepoAreBuilt() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();

        List<String> ids = new ArrayList<>();
        for (Route route : routes) {
            ids.add(route.getId());
        }
        for (String service : SERVICES) {
            assertThat(ids).contains(service, service + "-docs");
        }
        assertThat(routes).hasSize(SERVICES.length * 2);
    }

    @Test
    void retryFilterRetriesOnlyGetOn502And503() {
        for (RouteDefinition definition : gatewayProperties.getRoutes()) {
            if (definition.getId().endsWith("-docs")) {
                continue;
            }
            FilterDefinition retry = findFilter(definition, "Retry");
            assertThat(retry).as("Retry filter on route %s", definition.getId()).isNotNull();
            assertThat(findFilter(definition, "CircuitBreaker")).isNotNull();

            // Bind the YAML args exactly like RouteDefinitionRouteLocator does when it builds the route
            RetryGatewayFilterFactory.RetryConfig config = configurationService.with(retryFactory)
                    .name(retry.getName())
                    .properties(retry.getArgs())
                    .bind();

            assertThat(config.getRetries()).isEqualTo(2);
            assertThat(config.getMethods()).containsExactly(HttpMethod.GET);
            assertThat(config.getStatuses()).containsExactlyInAnyOrder(HttpStatus.BAD_GATEWAY,
                    HttpStatus.SERVICE_UNAVAILABLE);
            // Default series SERVER_ERROR would make every 5xx retryable; config-repo clears it on purpose.
            assertThat(config.getSeries()).isEmpty();
        }
    }

    @Test
    void swaggerUiListsEveryServiceDocument() {
        String[] urls = new String[SERVICES.length];
        for (int i = 0; i < SERVICES.length; i++) {
            urls[i] = "/docs/" + SERVICES[i] + "/v3/api-docs";
        }
        // springdoc sorts the entries by name, so compare as sets
        webTestClient.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[*].name").value(Matchers.containsInAnyOrder((Object[]) SERVICES))
                .jsonPath("$.urls[*].url").value(Matchers.containsInAnyOrder((Object[]) urls))
                .jsonPath("$['urls.primaryName']").isEqualTo("product-service");
    }

    @Test
    void timeLimiterDoesNotCutSlowCheckoutShort() {
        assertThat(timeLimiterRegistry.getDefaultConfig().getTimeoutDuration()).isEqualTo(Duration.ofSeconds(12));

        // Behavioural check: with Resilience4j's 1s default this 1.5s call would be replaced by the fallback.
        String result = circuitBreakerFactory.create("order-service")
                .run(Mono.delay(Duration.ofMillis(1500)).thenReturn("completed"),
                        error -> Mono.just("fallback: " + error.getClass().getSimpleName()))
                .block();

        assertThat(result).isEqualTo("completed");
    }

    @Test
    void healthEndpointIsUp() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private FilterDefinition findFilter(RouteDefinition definition, String name) {
        for (FilterDefinition filter : definition.getFilters()) {
            if (name.equals(filter.getName())) {
                return filter;
            }
        }
        return null;
    }
}
