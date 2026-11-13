# Mã chuyển khoản TT/NT theo từng yêu cầu

Định dạng 18 ký tự áp dụng từ migration V9. Hóa đơn tạo từ quầy hiện hành không yêu cầu mã sinh viên.

## Mẫu mã

Mã có đúng **18 ký tự**, không có khoảng trắng. Phiếu nạp vẫn gồm loại giao dịch (2) + MSSV (8) + số yêu cầu (8). Hóa đơn mới không có MSSV dùng tiền tố tương thích `TTAT000000` + số hóa đơn (8); `AT000000` ở đây chỉ là phần cố định của mã, không xác định sinh viên.

| Nghiệp vụ | Ví dụ | Ý nghĩa |
| --- | --- | --- |
| Thanh toán hóa đơn mới | `TTAT00000000000125` | Hóa đơn HD00000125; không chứa MSSV |
| Hóa đơn cũ hoặc yêu cầu cũ có MSSV | `TTAT01020300000125` | Mã cũ của sinh viên AT010203, hóa đơn HD00000125 |
| Nạp tiền vào thẻ | `NTCT07035200000842` | Sinh viên CT070352, phiếu nạp số 842 |

Regex đầy đủ: `(?:TT|NT)(?:AT|CT|DT)[0-9]{14}`. Tám chữ số cuối xác định số yêu cầu. Với phiếu nạp và hóa đơn cũ, sáu chữ số trước đó thuộc MSSV; với hóa đơn mới không có MSSV, chúng là `000000`.

Backend dùng ID hóa đơn/phiếu nạp từ sequence PostgreSQL để sinh phần số, giới hạn 1..99.999.999; thêm số 0 ở đầu cho đủ 8 chữ số. Không cắt số, modulo, reset theo ngày hay tái sử dụng sau hủy/xóa. Hết dải trả PAYMENT_CODE_EXHAUSTED, không phát mã trùng. Unique index trên mã mới được giữ trong bảng chứng từ dù đối tượng gốc bị xóa. Không reset sequence của môi trường vận hành.

Tạo hóa đơn từ quầy mới không cần MSSV hoặc thẻ. Trả bằng thẻ cần xác thực ECC, PIN và đủ số dư; danh tính thẻ được gắn khi bắt đầu thanh toán. Yêu cầu từ phiên bản cũ vẫn có thể gửi MSSV và giữ kiểm tra đúng thẻ tương ứng. Chuyển khoản không yêu cầu thẻ đang ở đầu đọc.

## Ghép giao dịch và hiệu lực

Backend tra **toàn bộ mã 18 ký tự** để tìm đúng tham chiếu yêu cầu. Không bỏ 8 số cuối, không fallback theo MSSV, yêu cầu mới nhất hoặc số tiền. Thời gian chỉ dùng để kiểm tra hiệu lực của đúng yêu cầu đã tìm được.

Ví dụ hóa đơn cũ HD00000125 đã hủy, hóa đơn mới HD00000126 vẫn mở và cùng số tiền: chuyển khoản mới bằng QR `TTAT01020300000125` vẫn thuộc hóa đơn cũ, được giữ REVIEW; không thể trả cho HD00000126. Phiếu nạp áp dụng tương tự.

Một webhook chỉ có tác động tài chính khi:

1. Xác thực hợp lệ; ngân hàng, tài khoản/VA đúng cấu hình; giao dịch tiền vào.
2. Nội dung chứa đúng một mã đầy đủ. Trường code của SePay, nếu có, phải khớp nguyên mã trong content. Mẫu cũ, mã bị cắt, sai sinh viên hoặc số yêu cầu không tồn tại được đưa đối soát.
3. Mã trỏ đúng yêu cầu; đúng số tiền; thời điểm ngân hàng nằm trong thời hạn yêu cầu.
4. Tại lúc backend xử lý dưới khóa dữ liệu, yêu cầu chưa hết hạn/đóng. Nạp tiền còn WAITING_BANK; hóa đơn chưa có kết quả cuối và phiên quầy còn hiệu lực.
5. ID/tham chiếu ngân hàng chưa được sử dụng. Gửi lặp chỉ có một tác động.

Chuyển trước hạn nhưng webhook mới đến sau hạn cũng không được tự cộng tiền/ghi PAID. Webhook đã xác thực nhưng không đủ điều kiện nghiệp vụ vẫn trả **HTTP 200, {"success":true}**, giữ chứng từ REVIEW và không ghi tiền. Sai xác thực/tài khoản/payload tiếp tục bị từ chối như trước. HTTP 200 chỉ là xác nhận đã nhận thông báo.

Đúng mã của yêu cầu còn hiệu lực nhưng sai số tiền thì yêu cầu không được thanh toán/nạp tự động. Khi hóa đơn đang trừ tiền thẻ, hệ thống dùng cơ chế hủy và hoàn bù trên thẻ hiện có. Nội dung thiếu mã hoặc mã không khớp được lưu để đối soát mà không hủy yêu cầu nào. Vào **Admin → Giao dịch** để xem bảng chung gồm nạp tiền, thanh toán hóa đơn bằng thẻ, thanh toán hóa đơn bằng chuyển khoản và các khoản **Không rõ**; có thể tìm theo mã tham chiếu, ID thẻ hoặc mã sinh viên. Admin dùng chứng từ để xử lý hoàn tiền ngân hàng.

Một giao dịch đã được chấp nhận trước hạn vẫn được phục hồi đúng yêu cầu; replay sau hạn không tạo lần ghi tiền thứ hai. Khi nạp bình thường, quầy giữ kết nối và phiên thẻ cho đến khi ghi xong hoặc hủy/hết hạn trước lúc nhận tiền. Nếu đầu đọc hay đường truyền gặp sự cố, khoản BANK_RECEIVED được giữ để phục hồi trên đúng thẻ khi kết nối/xác thực lại. CARD_PROCESSING và biên nhận PENDING_CARD đã được nhận hợp lệ tiếp tục chờ kết quả thẻ theo cơ chế hiện có, không chốt thành công hai phương thức.

## Tạo lại và tải lại QR

- Retry cùng yêu cầu tạo hóa đơn trả cùng hóa đơn và mã; tải lại ảnh QR cũng giữ nguyên mã và thời hạn.
- Tạo lại cùng phiếu nạp WAITING_BANK/cùng số tiền trả cùng phiếu/mã. Muốn đổi số tiền thì hủy phiếu trước.
- Hóa đơn/phiếu nạp mới có số mới, kể cả cùng sinh viên và số tiền. Không còn khoảng chờ sang giây kế tiếp.
- Có thể phân biệt nhiều hóa đơn của cùng sinh viên ở các quầy khác nhau. Thanh toán vào một mã chỉ tác động đúng hóa đơn đó.
- Khi xóa thẻ/hóa đơn, tham chiếu giữ danh tính gốc để đối soát. Không đổi chủ chứng từ cho thẻ mới cùng MSSV.

## Cấu hình SePay Dashboard

1. Vào **Cấu hình Công ty → Cấu hình chung → Cấu trúc mã thanh toán**. Bật nhận diện mã thanh toán.
2. Sửa sáu mẫu dưới đây. Nếu đã có mẫu V8 hậu tố 6 chữ số, đổi cả tối thiểu/tối đa thành 14; không giữ thêm bản mẫu 6 chữ số cùng tiền tố vì có thể trích thiếu mã.

| Tiền tố | Hậu tố tối thiểu | Hậu tố tối đa | Loại ký tự | Trạng thái |
| --- | --- | --- | --- | --- |
| TTAT | 14 | 14 | Số nguyên | Đang hoạt động |
| TTCT | 14 | 14 | Số nguyên | Đang hoạt động |
| TTDT | 14 | 14 | Số nguyên | Đang hoạt động |
| NTAT | 14 | 14 | Số nguyên | Đang hoạt động |
| NTCT | 14 | 14 | Số nguyên | Đang hoạt động |
| NTDT | 14 | 14 | Số nguyên | Đang hoạt động |

3. Tắt mẫu TTM/CTHD cũ và mẫu ngắn/bao quát có thể bắt trước sáu mẫu này; lưu cấu hình. SePay dừng ở mẫu khớp đầu tiên. 14 là phần sau tiền tố 4 ký tự, tổng mã là 18.
4. Vào **Tích hợp → Webhooks → sửa webhook của project**. Giữ URL hiện có kết thúc bằng `/webhook`, ngân hàng và đúng tài khoản/VA đang dùng. Chọn **Tiền vào**, để trống bộ lọc theo tiền tố và tắt **Bỏ qua giao dịch không có mã** để SePay gửi cả các khoản cần đối soát.
5. Ở **Bảo mật**, giữ **API Key** khớp cấu hình project. Chỉ nhập giá trị khóa; SePay tự gửi Authorization: Apikey ... . Không đổi sang phương thức khác nếu backend chưa hỗ trợ.
6. Lưu webhook. Cấu hình Test mode và Live tách biệt; kiểm tra đúng môi trường. Mã nguồn không tự sửa Dashboard.

Giới hạn tiền tố/hậu tố và thứ tự trích code: [tài liệu cấu hình mã SePay](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan). Các mục tài khoản/VA, bộ lọc và API Key: [tài liệu tạo webhook](https://developer.sepay.vn/vi/sepay-webhooks/tao-webhook).

## Kiểm tra sau cấu hình

- Trước khi chuyển tiền thật, chạy đúng backend/desktop mới và tạo QR mới. Nội dung phải có đủ 18 ký tự. Không dùng ảnh QR cũ.
- Thử ở Test mode trước. Trường code phải đúng nguyên mã, ví dụ `TTAT00000000000125`, không bị cắt còn `TTAT000000`. Hóa đơn phải còn hiệu lực và số tiền phải khớp khi gửi sự kiện.
- Nút Gửi thử của SePay có thể gửi probe id=0; HTTP 200 của probe chỉ chứng minh đường truyền/xác thực, không tạo thanh toán.
- Với giao dịch thực cần đối chiếu trạng thái nguồn PAID/BANK_RECEIVED/CREDITED và biên nhận tương ứng; HTTP 200 không đủ chứng minh đã ghi tiền.
- QR cũ hết hạn/hủy phải vào REVIEW, yêu cầu mới cùng số tiền không được thay đổi; giao dịch gửi lặp không ghi lần hai.

## Nâng cấp và dữ liệu cũ

Giữ nguyên V1–V8; V9 mở rộng trường mã, giữ mã 10 ký tự cũ làm chứng từ và thêm unique index cho mã 18 ký tự. Mã cũ vẫn được lưu lịch sử nhưng không được parser nhận cho giao dịch mới. Không backfill/rebind mã lịch sử thành yêu cầu mới.

V9 làm hết hiệu lực các topup WAITING_BANK dùng mẫu cũ để nhân viên tạo phiếu/QR mới. BANK_RECEIVED/CREDITED, bank_events, số dư và journal debit/hoàn tác được giữ. Hóa đơn cũ ngừng hiển thị QR cũ; phiên quầy cũ được xử lý bằng cơ chế restart/recovery hiện có, không trực tiếp bỏ qua debit chưa rõ kết quả.

Backup CSDL và dừng các bản backend/desktop cũ trước khi nâng cấp đồng bộ. Migration kiểm thử trên ttm_test không tự áp dụng vào môi trường vận hành. Luồng Live với mã mới cần nghiệm thu riêng sau khi cài bản mới và sửa bộ lọc SePay.
