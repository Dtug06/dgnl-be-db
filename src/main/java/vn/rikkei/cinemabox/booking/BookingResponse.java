package vn.rikkei.cinemabox.booking;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record BookingResponse(
        Long id,
        Long customerId,
        Long showtimeId,
        Integer quantity,
        Long totalAmount,
        String status,
        String paymentRef,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getCustomer().getId(),
                booking.getShowtime().getId(),
                booking.getQuantity(),
                booking.getTotalAmount(),
                booking.getStatus(),
                booking.getPaymentRef(),
                booking.getCreatedAt());
    }
}
