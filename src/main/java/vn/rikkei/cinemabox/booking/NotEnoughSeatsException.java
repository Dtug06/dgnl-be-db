package vn.rikkei.cinemabox.booking;

public class NotEnoughSeatsException extends RuntimeException {

    public NotEnoughSeatsException(Long showtimeId) {
        super("Suất chiếu id=" + showtimeId + " không đủ ghế");
    }
}
