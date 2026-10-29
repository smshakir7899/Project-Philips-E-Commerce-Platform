package com.philips.ecommerce.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real config server against the real ../config-repo directory (surefire runs with the module directory
 * as working dir) and checks what a client would receive during startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.config.server.native.search-locations=file:../config-repo/")
class ConfigServerApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void servesSharedApplicationYmlToEveryClient() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/product-service/default", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("name")).isEqualTo("product-service");

        List<Map<String, Object>> propertySources = (List<Map<String, Object>>) body.get("propertySources");
        assertThat(propertySources).isNotEmpty();

        // The shared file is always the LAST (lowest-precedence) source; it must carry the Eureka URL placeholder,
        // unresolved, because the client resolves ${EUREKA_URL:...} with its own environment.
        Map<String, Object> shared = findSource(propertySources, "application.yml");
        assertThat(shared).as("application.yml property source").isNotNull();
        Map<String, Object> source = (Map<String, Object>) shared.get("source");
        assertThat(source).containsKey("eureka.client.service-url.defaultZone");
        assertThat(String.valueOf(source.get("eureka.client.service-url.defaultZone"))).contains("EUREKA_URL");
    }

    @Test
    void servesInventoryServiceConfiguration() {
        ResponseEntity<String> response = restTemplate.getForEntity("/inventory-service/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"name\":\"inventory-service\"");
    }

    @Test
    void exposesHealthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    private Map<String, Object> findSource(List<Map<String, Object>> sources, String fileName) {
        for (Map<String, Object> candidate : sources) {
            String name = String.valueOf(candidate.get("name"));
            // Native backend names sources like "file:../config-repo/application.yml" (or a "Config resource ..." form)
            if (name.endsWith(fileName) || name.contains("/" + fileName)) {
                return candidate;
            }
        }
        return null;
    }
}
