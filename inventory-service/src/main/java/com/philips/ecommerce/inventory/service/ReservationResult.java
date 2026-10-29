package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.inventory.dto.ReservationResponse;

/** Outcome of an idempotent reserve call: the controller answers 201 when created, 200 when replayed. */
public final class ReservationResult {

    private final ReservationResponse reservation;
    private final boolean created;

    private ReservationResult(ReservationResponse reservation, boolean created) {
        this.reservation = reservation;
        this.created = created;
    }

    public static ReservationResult created(ReservationResponse reservation) {
        return new ReservationResult(reservation, true);
    }

    public static ReservationResult replayed(ReservationResponse reservation) {
        return new ReservationResult(reservation, false);
    }

    public ReservationResponse getReservation() {
        return reservation;
    }

    public boolean isCreated() {
        return created;
    }
}
