package vn.rikkei.cinemabox.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.rikkei.cinemabox.common.ResourceNotFoundException;
import vn.rikkei.cinemabox.customer.Customer;
import vn.rikkei.cinemabox.customer.CustomerRepository;
import vn.rikkei.cinemabox.payment.PaymentGateway;
import vn.rikkei.cinemabox.showtime.Showtime;
import vn.rikkei.cinemabox.showtime.ShowtimeRepository;

@Service
@Transactional
public class BookingService {

    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";
    private static final BigDecimal GOLD_PRICE_FACTOR = new BigDecimal("0.90");

    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final ShowtimeRepository showtimeRepository;
    private final PaymentGateway paymentGateway;

    public BookingService(BookingRepository bookingRepository, CustomerRepository customerRepository,
                          ShowtimeRepository showtimeRepository, PaymentGateway paymentGateway) {
        this.bookingRepository = bookingRepository;
        this.customerRepository = customerRepository;
        this.showtimeRepository = showtimeRepository;
        this.paymentGateway = paymentGateway;
    }

    public BookingResponse create(CreateBookingRequest request) {
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khách hàng id=" + request.customerId()));
        Showtime showtime = lockShowtime(request.showtimeId());
        int quantity = request.quantity();
        if (showtime.getAvailableSeats() < quantity) {
            throw new NotEnoughSeatsException(showtime.getId());
        }

        BigDecimal total = BigDecimal.valueOf(showtime.getPrice()).multiply(BigDecimal.valueOf(quantity));
        if ("GOLD".equals(customer.getMemberLevel())) {
            total = total.multiply(GOLD_PRICE_FACTOR).setScale(0, RoundingMode.FLOOR);
        }
        long totalAmount = total.longValueExact();

        showtime.setAvailableSeats(showtime.getAvailableSeats() - quantity);
        String paymentRef = paymentGateway.charge(customer.getId(), totalAmount);

        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setShowtime(showtime);
        booking.setQuantity(quantity);
        booking.setTotalAmount(totalAmount);
        booking.setStatus(CONFIRMED);
        booking.setPaymentRef(paymentRef);
        return BookingResponse.from(bookingRepository.save(booking));
    }

    public BookingResponse cancel(Long id) {
        Booking booking = bookingRepository.findLockedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vé id=" + id));
        if (!CONFIRMED.equals(booking.getStatus())) {
            throw new InvalidBookingStateException(id);
        }

        Showtime showtime = lockShowtime(booking.getShowtime().getId());
        showtime.setAvailableSeats(showtime.getAvailableSeats() + booking.getQuantity());
        booking.setStatus(CANCELLED);
        return BookingResponse.from(booking);
    }

    private Showtime lockShowtime(Long id) {
        return showtimeRepository.findLockedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy suất chiếu id=" + id));
    }
}
