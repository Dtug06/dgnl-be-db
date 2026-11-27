package vn.rikkei.cinemabox.showtime;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record ShowtimeResponse(
        Long id,
        String movieTitle,
        String roomName,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startTime,
        Long price,
        Integer availableSeats) {

    public static ShowtimeResponse from(Showtime showtime) {
        return new ShowtimeResponse(
                showtime.getId(),
                showtime.getMovie().getTitle(),
                showtime.getRoom().getName(),
                showtime.getStartTime(),
                showtime.getPrice(),
                showtime.getAvailableSeats());
    }
}
