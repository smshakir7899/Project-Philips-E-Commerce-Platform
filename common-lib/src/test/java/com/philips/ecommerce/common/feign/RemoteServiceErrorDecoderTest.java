package com.philips.ecommerce.common.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteServiceErrorDecoderTest {

    private final RemoteServiceErrorDecoder decoder =
            new RemoteServiceErrorDecoder(new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void keepsDownstreamConflictCodeAndDetails() {
        String body = "{\"status\":409,\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"Not enough\",\"details\":{\"productId\":3}}";
        Exception ex = decoder.decode("InventoryClient#reserve", response(409, body));

        assertThat(ex).isInstanceOf(RemoteServiceException.class);
        RemoteServiceException remote = (RemoteServiceException) ex;
        assertThat(remote.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(remote.getCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(remote.isConflict()).isTrue();
        assertThat(remote.getDetails()).containsEntry("productId", 3);
    }

    @Test
    void mapsServerErrorsToBadGateway() {
        RemoteServiceException remote = (RemoteServiceException) decoder.decode("ProductClient#get", response(500, ""));

        assertThat(remote.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(remote.getCode()).isEqualTo("DOWNSTREAM_ERROR");
    }

    @Test
    void toleratesNonJsonBodies() {
        RemoteServiceException remote = (RemoteServiceException) decoder.decode("X#y", response(404, "<html>nope</html>"));

        assertThat(remote.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(remote.getCode()).isEqualTo("HTTP_404");
        assertThat(remote.isNotFound()).isTrue();
    }

    private Response response(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "http://svc/x", Collections.emptyMap(),
                null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).request(request).headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8).build();
    }
}
