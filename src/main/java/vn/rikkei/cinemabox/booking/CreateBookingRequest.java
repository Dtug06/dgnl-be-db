package vn.rikkei.cinemabox.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateBookingRequest(
        @NotNull(message = "Mã khách hàng là bắt buộc")
        Long customerId,
        @NotNull(message = "Mã suất chiếu là bắt buộc")
        Long showtimeId,
        @NotNull(message = "Số vé là bắt buộc")
        @Min(value = 1, message = "Số vé phải từ 1 đến 10")
        @Max(value = 10, message = "Số vé phải từ 1 đến 10")
        Integer quantity) {
}
