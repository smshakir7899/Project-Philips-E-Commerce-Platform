package com.philips.ecommerce.common.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.dto.ApiErrorResponse;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Global Feign ErrorDecoder: parses the downstream {@link ApiErrorResponse} body and converts it into a
 * {@link RemoteServiceException}. 4xx keep their status and code; 5xx become 502 DOWNSTREAM_ERROR unless the
 * downstream already reported 503 (then 503 and its code are kept).
 */
public class RemoteServiceErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(RemoteServiceErrorDecoder.class);

    private final ObjectMapper objectMapper;

    public RemoteServiceErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        int remoteStatus = response.status();
        ApiErrorResponse body = readBody(response);
        String code = body != null && body.getCode() != null ? body.getCode() : "HTTP_" + remoteStatus;
        String message = body != null && body.getMessage() != null
                ? body.getMessage() : "Downstream call " + methodKey + " failed with HTTP " + remoteStatus;

        HttpStatus status;
        if (remoteStatus == 503) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (remoteStatus >= 500) {
            status = HttpStatus.BAD_GATEWAY;
            code = "DOWNSTREAM_ERROR";
        } else {
            HttpStatus resolved = HttpStatus.resolve(remoteStatus);
            status = resolved != null ? resolved : HttpStatus.BAD_GATEWAY;
        }
        log.info("Feign call {} returned {} {}", methodKey, remoteStatus, code);

        RemoteServiceException exception = new RemoteServiceException(status, code, message, remoteStatus, methodKey);
        if (body != null && body.getDetails() != null) {
            for (Map.Entry<String, Object> entry : body.getDetails().entrySet()) {
                exception.withDetail(entry.getKey(), entry.getValue());
            }
        }
        return exception;
    }

    private ApiErrorResponse readBody(Response response) {
        if (response.body() == null) {
            return null;
        }
        try (Reader reader = response.body().asReader(StandardCharsets.UTF_8)) {
            String text = Util.toString(reader);
            if (text.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(text, ApiErrorResponse.class);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not parse error body of downstream response", e);
            return null;
        }
    }
}
