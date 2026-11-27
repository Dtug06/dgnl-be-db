# Bài làm CinemaBox – Database + Backend

Họ tên:Nguyễn Doanh Tuấn
Mã sinh viên:B24DTCN389
Lớp:CNTT-4

## Pha 2 (được dùng AI)

### Cách tôi chống bán vượt số ghế khi nhiều người đặt cùng lúc

Tôi dùng `PESSIMISTIC_WRITE` để khóa dòng của suất chiếu ngay khi lấy dữ liệu trong transaction đặt vé. Nếu hai người đặt cùng suất, người đến sau phải đợi giao dịch trước kết thúc rồi mới đọc số ghế còn lại. Vì khóa nằm trên từng dòng, các suất khác vẫn có thể xử lý song song. Với chức năng hủy, tôi khóa cả dòng vé để nếu hai yêu cầu hủy cùng một vé thì chỉ yêu cầu đầu tiên trả ghế. Tôi kiểm tra bằng cách gửi nhiều yêu cầu cùng lúc và đối chiếu trong H2 rằng số ghế còn lại cộng số vé `CONFIRMED` bán thêm bằng số ghế ban đầu.

### Cách tôi hoàn tác khi thanh toán lỗi

Tôi giảm số ghế trong transaction và gọi cổng thanh toán trước khi lưu vé. Nếu thanh toán thành công, tôi lưu vé ở trạng thái `CONFIRMED` cùng mã giao dịch. Nếu cổng thanh toán ném `PaymentFailedException`, tôi để lỗi truyền ra khỏi service để Spring rollback rồi dùng bộ xử lý lỗi chung để trả HTTP 402 với mã `PAYMENT_FAILED`. Trong test vượt hạn mức, tôi đối chiếu số ghế trước và sau khi gọi API và kiểm tra rằng không có vé mới.

### Các test tôi dùng để kiểm chứng

Trong lớp `BookingApiTest`, tôi gọi API qua MockMvc và đọc trực tiếp dữ liệu H2 để kiểm tra cả phản hồi HTTP lẫn trạng thái lưu trong cơ sở dữ liệu.

- `goldDiscountRoundsDownAndCanBookLastSeat` kiểm tra giá GOLD làm tròn xuống và đặt được đúng bằng số ghế còn lại.
- `paymentFailureRollsBackSeatsAndLeavesNoBooking` kiểm tra HTTP 402, số ghế giữ nguyên và không phát sinh vé.
- `invalidInputMissingRecordsAndInsufficientSeatsReturnExpectedErrors` kiểm tra HTTP 400, 404, 409 và số ghế sau các yêu cầu bị từ chối.
- `concurrentBookingsSellExactlyTheAvailableSeats` gửi nhiều yêu cầu cùng lúc vào hai suất, kiểm tra số ghế trên từng suất và chỉ bán đến khi hết ghế.
- `concurrentPaymentFailuresDoNotConsumeSeats` trộn yêu cầu thành công với giao dịch vượt hạn mức và đối chiếu số ghế còn lại với số vé đã bán.
- `concurrentCancellationReturnsSeatsOnlyOnce` cho hai yêu cầu hủy cùng một vé, kiểm tra một lần hoàn ghế, mã lỗi khi hủy lặp và khi không tìm thấy vé.
- `databaseRejectsNegativeAvailableSeats` thử ghi số ghế âm trực tiếp và kiểm tra ràng buộc ở cơ sở dữ liệu.

Tôi nhờ AI chạy `mvn clean test` trên JDK 21. Cả 9 test đều đạt, gồm 7 test đặt vé được bổ sung và 2 test có sẵn.

### Nhật ký dùng AI

| # | Tôi hỏi AI điều gì | AI gợi ý gì | Tôi giữ, sửa hoặc bỏ gì và vì sao |
|---|---|---|---|
| 1 | Tôi nhờ AI làm mục 4.1.4 theo cấu trúc API mẫu. | AI đề xuất tạo `ShowtimeController`, `ShowtimeService` và `ShowtimeResponse`, dùng `@JsonFormat` cho giờ chiếu và dùng lại định dạng lỗi 404 chung. | Tôi giữ cách triển khai này. Khi gọi thử trên H2, AI xác nhận phản hồi 200 có đủ sáu trường và ID không tồn tại trả 404 với mã `NOT_FOUND`. |
| 2 | Tôi nhờ AI làm Pha 2 theo PDF. Tôi cần xử lý tình huống nhiều người đặt cùng suất và giao dịch bị từ chối. | AI đề xuất dùng transaction và khóa ghi trên suất chiếu, tính tiền GOLD bằng `BigDecimal`, rồi gọi cổng thanh toán trước khi lưu vé. | Tôi giữ khóa đến khi transaction kết thúc để yêu cầu tiếp theo đọc được số ghế sau khi giao dịch trước hoàn tất. Tôi dùng test giao dịch vượt hạn mức để đối chiếu phản hồi 402, số ghế còn lại và số vé trong H2. |
| 3 | Với phần nâng cao, tôi nhờ AI hỗ trợ cách hủy vé an toàn và cách kiểm tra ràng buộc số ghế. | AI đề xuất khóa dòng vé khi hủy, thêm `CHECK (available_seats >= 0)` và dùng nhiều luồng gửi yêu cầu trong test. | Tôi chọn giữ cả khóa dòng vé và ràng buộc trong H2. Trong test hủy đồng thời, tôi đối chiếu hai phản hồi 200 và 409 với số ghế thực tế. Tôi dùng thêm một câu lệnh SQL để kiểm tra H2 từ chối số ghế âm. |
