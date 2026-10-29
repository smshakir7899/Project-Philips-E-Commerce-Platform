package com.philips.ecommerce.product.dto;

import com.philips.ecommerce.common.exception.BadRequestException;

import java.util.Locale;

/**
 * Whitelist of sortable fields. Never pass a client-provided sort property straight into a query:
 * it would allow sorting by unindexed columns (or lazy associations) and, with native SQL, would be an
 * injection vector. Each field knows its JPA property name and its SQL column name (for native queries).
 */
public enum ProductSortField {

    NAME("name", "name"),
    PRICE("price", "price"),
    CREATED_AT("createdAt", "created_at");

    private final String property;
    private final String column;

    ProductSortField(String property, String column) {
        this.property = property;
        this.column = column;
    }

    public String getProperty() {
        return property;
    }

    public String getColumn() {
        return column;
    }

    /** @throws BadRequestException INVALID_SORT_FIELD when the field is not whitelisted */
    public static ProductSortField fromProperty(String value) {
        for (ProductSortField field : values()) {
            if (field.property.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return field;
            }
        }
        throw new BadRequestException("INVALID_SORT_FIELD",
                "Unsupported sort field '" + value + "'. Allowed: name, price, createdAt");
    }

    @Override
    public String toString() {
        return property.toLowerCase(Locale.ROOT);
    }
}
