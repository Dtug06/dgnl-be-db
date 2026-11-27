package vn.rikkei.cinemabox;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class BookingApiTest {

    private static final long STANDARD_CUSTOMER = 2;
    private static final long GOLD_CUSTOMER = 1;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private final List<Long> showtimeIds = new ArrayList<>();

    @AfterEach
    void removeTestData() {
        for (long showtimeId : showtimeIds) {
            jdbc.update("DELETE FROM bookings WHERE showtime_id = ?", showtimeId);
            jdbc.update("DELETE FROM showtimes WHERE id = ?", showtimeId);
        }
    }

    @Test
    void goldDiscountRoundsDownAndCanBookLastSeat() throws Exception {
        long showtimeId = createShowtime(1, 101);

        book(GOLD_CUSTOMER, showtimeId, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.customerId").value(GOLD_CUSTOMER))
                .andExpect(jsonPath("$.showtimeId").value(showtimeId))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.totalAmount").value(90))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentRef").value(matchesPattern("PAY-[0-9A-F]{8}")))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("[0-9-]{10}T[0-9:]{8}")));
        assertEquals(90, jdbc.queryForObject(
                "SELECT total_amount FROM bookings WHERE showtime_id = ?", Long.class, showtimeId));
        assertInventory(showtimeId, 1, 0, 1);
    }

    @Test
    void paymentFailureRollsBackSeatsAndLeavesNoBooking() throws Exception {
        long showtimeId = createShowtime(12, 120_000);

        book(STANDARD_CUSTOMER, showtimeId, 10)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.path").value("/api/bookings"))
                .andExpect(jsonPath("$.details").isEmpty());
        assertInventory(showtimeId, 12, 12, 0);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE showtime_id = ?", Integer.class, showtimeId));
    }

    @Test
    void invalidInputMissingRecordsAndInsufficientSeatsReturnExpectedErrors() throws Exception {
        long showtimeId = createShowtime(1, 120_000);

        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":11}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field",
                        containsInAnyOrder("customerId", "showtimeId", "quantity")));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        book(9999, showtimeId, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        book(STANDARD_CUSTOMER, 9999, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        book(STANDARD_CUSTOMER, showtimeId, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_ENOUGH_SEATS"));
        assertInventory(showtimeId, 1, 1, 0);
    }

    @Test
    void concurrentBookingsSellExactlyTheAvailableSeats() throws Exception {
        long showtimeId = createShowtime(3, 120_000);
        long otherShowtimeId = createShowtime(1, 100_000);
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(() -> book(STANDARD_CUSTOMER, showtimeId, 1).andReturn().getResponse().getStatus());
        }
        requests.add(() -> book(STANDARD_CUSTOMER, otherShowtimeId, 1).andReturn().getResponse().getStatus());

        List<Integer> statuses = runConcurrently(requests);

        assertEquals(List.of(201, 201, 201, 409, 409, 409), statuses.subList(0, 6).stream().sorted().toList());
        assertEquals(201, statuses.get(6));
        assertInventory(showtimeId, 3, 0, 3);
        assertInventory(otherShowtimeId, 1, 0, 1);
    }

    @Test
    void concurrentPaymentFailuresDoNotConsumeSeats() throws Exception {
        long showtimeId = createShowtime(13, 120_000);
        Callable<Integer> success = () -> book(STANDARD_CUSTOMER, showtimeId, 1)
                .andExpect(status().isCreated()).andReturn().getResponse().getStatus();
        Callable<Integer> failure = () -> book(STANDARD_CUSTOMER, showtimeId, 10)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("PAYMENT_FAILED"))
                .andReturn().getResponse().getStatus();

        assertEquals(List.of(201, 402, 201, 402, 201),
                runConcurrently(List.of(success, failure, success, failure, success)));
        assertInventory(showtimeId, 13, 10, 3);
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE showtime_id = ?", Integer.class, showtimeId));
    }

    @Test
    void concurrentCancellationReturnsSeatsOnlyOnce() throws Exception {
        long showtimeId = createShowtime(2, 100_000);
        String createdBody = book(STANDARD_CUSTOMER, showtimeId, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(200_000))
                .andReturn().getResponse().getContentAsString();
        long bookingId = json.readTree(createdBody).get("id").asLong();
        Callable<Integer> cancel = () -> mvc.perform(post("/api/bookings/{id}/cancel", bookingId))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        jsonPath("$.status").value("CANCELLED").match(result);
                        jsonPath("$.id").value(bookingId).match(result);
                        jsonPath("$.paymentRef").value(json.readTree(createdBody).get("paymentRef").asText())
                                .match(result);
                    } else {
                        jsonPath("$.error").value("INVALID_STATE").match(result);
                    }
                })
                .andReturn().getResponse().getStatus();

        assertEquals(List.of(200, 409), runConcurrently(List.of(cancel, cancel)).stream().sorted().toList());
        assertInventory(showtimeId, 2, 2, 0);
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId));
        mvc.perform(post("/api/bookings/{id}/cancel", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void databaseRejectsNegativeAvailableSeats() {
        long showtimeId = createShowtime(1, 120_000);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE showtimes SET available_seats = -1 WHERE id = ?", showtimeId));
        assertInventory(showtimeId, 1, 1, 0);
    }

    private long createShowtime(int seats, long price) {
        long showtimeId = new SimpleJdbcInsert(jdbc)
                .withTableName("showtimes")
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(Map.of("movie_id", 1, "room_id", 1,
                        "start_time", java.sql.Timestamp.valueOf("2026-12-07 19:00:00"),
                        "price", price, "available_seats", seats))
                .longValue();
        showtimeIds.add(showtimeId);
        return showtimeId;
    }

    private ResultActions book(long customerId, long showtimeId, int quantity) throws Exception {
        return mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "customerId", customerId, "showtimeId", showtimeId, "quantity", quantity))));
    }

    private void assertInventory(long showtimeId, int initialSeats, int expectedRemaining, int expectedSold) {
        int remaining = jdbc.queryForObject(
                "SELECT available_seats FROM showtimes WHERE id = ?", Integer.class, showtimeId);
        int sold = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM bookings WHERE showtime_id = ? AND status = 'CONFIRMED'",
                Integer.class, showtimeId);
        assertEquals(expectedRemaining, remaining);
        assertEquals(expectedSold, sold);
        assertEquals(initialSeats, remaining + sold);
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM bookings
                WHERE showtime_id = ? AND status = 'CONFIRMED'
                  AND (payment_ref IS NULL OR TRIM(payment_ref) = '')
                """, Integer.class, showtimeId));
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (Callable<Integer> request : requests) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return request.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pending) {
                statuses.add(result.get(15, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }
}
