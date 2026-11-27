package vn.rikkei.cinemabox.booking;

public class InvalidBookingStateException extends RuntimeException {

    public InvalidBookingStateException(Long bookingId) {
        super("Vé id=" + bookingId + " không ở trạng thái CONFIRMED");
    }
}
