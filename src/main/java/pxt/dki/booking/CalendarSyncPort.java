package pxt.dki.booking;

import pxt.dki.calendar.OutboxAction;

/**
 * Booking's outbound port to calendar synchronization. Implemented by the calendar domain;
 * called inside the booking transaction so the outbox row commits atomically with the booking.
 */
public interface CalendarSyncPort {

    void enqueue(Booking booking, OutboxAction action);

    /**
     * UPDATE with participant delta: room row as usual; personally, added users get a CREATE
     * copy, removed users a CANCEL, everyone still on the booking an UPDATE. CREATE rows are
     * written first so the batch converges in id order.
     */
    void enqueueUpdate(Booking booking, java.util.Collection<pxt.dki.user.User> added,
                       java.util.Collection<pxt.dki.user.User> removed);
}
