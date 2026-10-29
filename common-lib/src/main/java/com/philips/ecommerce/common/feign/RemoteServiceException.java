package com.philips.ecommerce.common.feign;

import com.philips.ecommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by OpenFeign clients when a downstream service answers with an error. It keeps the downstream HTTP status
 * and error code (e.g. 409 INSUFFICIENT_STOCK) so the caller can react to it, and so that - if not caught - the
 * error propagates to the original client with the same meaning.
 */
public class RemoteServiceException extends BusinessException {

    private final int remoteStatus;
    private final String methodKey;

    public RemoteServiceException(HttpStatus status, String code, String message, int remoteStatus, String methodKey) {
        super(status, code, message);
        this.remoteStatus = remoteStatus;
        this.methodKey = methodKey;
    }

    public int getRemoteStatus() {
        return remoteStatus;
    }

    public String getMethodKey() {
        return methodKey;
    }

    public boolean isNotFound() {
        return remoteStatus == 404;
    }

    public boolean isConflict() {
        return remoteStatus == 409;
    }

    public boolean isClientError() {
        return remoteStatus >= 400 && remoteStatus < 500;
    }
}
