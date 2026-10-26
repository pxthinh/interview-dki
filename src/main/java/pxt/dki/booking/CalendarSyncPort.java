package pxt.dki.booking;

import pxt.dki.calendar.OutboxAction;

/**
 * Booking's outbound port to calendar synchronization. Implemented by the calendar domain;
 * called inside the booking transaction so the outbox row commits atomically with the booking.
 */
public interface CalendarSyncPort {

    void enqueue(Booking booking, OutboxAction action);
}
