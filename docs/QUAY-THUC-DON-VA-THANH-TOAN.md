# Quầy thực đơn và thanh toán

Phiên bản chức năng: migration V6/V7/V8/V9, 07/11/2026.

## Quản lý danh mục và món

Nhân viên dùng trực tiếp tại quầy thanh toán, không đăng nhập admin. Backend vẫn yêu cầu thiết bị hợp lệ và phiên quầy đang hoạt động.

1. **Thêm danh mục**: nhập tên từ 1 đến 50 ký tự. Tên được bỏ khoảng trắng thừa; không tạo hai danh mục chỉ khác chữ hoa/thường.
2. Chọn **Danh mục** để lọc món; **Tất cả** xem toàn bộ. Kết hợp ô tìm kiếm với danh mục đang chọn.
3. **Thêm món**: chọn danh mục, nhập tên, chọn JPEG/PNG đúng **256 × 256 pixel**, nhập giá nguyên VNĐ dương rồi lưu. Không nhận ảnh 512 × 512 hoặc ảnh chữ nhật; app không tự thu/cắt ảnh sai kích thước. Giới hạn file 1 MiB.
4. Nút **Sửa** của món đổi danh mục, tên, giá và có thể thay ảnh bằng JPEG/PNG đúng 256 × 256 pixel. Nếu không chọn ảnh mới, ảnh hiện tại được giữ nguyên.
5. **Xóa món** xóa cứng bản ghi và ảnh. **Xóa danh mục** hiển thị “Danh mục này hiện có xx món! Tiếp tục xoá?” rồi xóa cứng danh mục và các món sau xác nhận. Nếu nội dung thay đổi trong lúc xác nhận, tải lại và xác nhận với số liệu mới.

Món có sẵn trước nâng cấp chưa có ảnh vẫn bán được. Hóa đơn đã tạo giữ tên/giá/số lượng lúc bán dù món nguồn bị sửa hoặc xóa. Giỏ chưa chốt sẽ được cập nhật và yêu cầu xem lại nếu thực đơn đã thay đổi.

## Tạo hóa đơn

Chọn món, chỉnh số lượng rồi nhấn **Tạo hóa đơn**. Nhập MSSV đầy đủ của sinh viên đã đăng ký (AT/CT/DT + sáu số). Mã thanh toán là TT + MSSV; thẻ dùng trả tiền phải thuộc đúng sinh viên trên hóa đơn.

Backend quyết định giá, tổng tiền và hạn 5 phút. Sau khi tạo, chuyển sang tab **Màn hình QR cho khách**: QR ở trái, chi tiết và tổng tiền ở phải. Hóa đơn không được thay đổi món sau khi chốt; hủy và tạo mới nếu cần sửa đơn.

Nếu mất phản hồi khi tạo hóa đơn, nhấn lại **Tạo hóa đơn** để truy vấn bằng cùng mã yêu cầu. Giao diện giữ giỏ trong lúc chưa xác định kết quả, không âm thầm tạo mã chuyển khoản thứ hai.

## Chuyển khoản

QR dùng cùng cấu hình ngân hàng/tài khoản/VA với nạp thẻ, điền sẵn tổng tiền và nội dung **TT + MSSV + 8 số hóa đơn**, ví dụ **TTAT01020300000125**. Không thay nội dung hoặc số tiền sau khi quét. QR lỗi tải có thể nhấn **Tải lại QR** trong thời hạn.

Khi webhook xác thực giao dịch đúng tài khoản, mã, số tiền và điều kiện hóa đơn, app tự chuyển sang **Đã thanh toán bằng chuyển khoản**. Không cần kết nối thẻ hoặc nhập PIN cho phương thức này. Nhân viên chỉ giao món theo kết quả backend đã xác nhận, không theo ảnh chụp chuyển tiền.

SePay Dashboard cần sáu mẫu TTAT/TTCT/TTDT/NTAT/NTCT/NTDT, hậu tố số đúng 14 ký tự (6 số MSSV + 8 số yêu cầu). Webhook Tiền vào của đúng tài khoản/VA để trống bộ lọc tiền tố và tắt **Bỏ qua giao dịch không có mã** để nhận cả giao dịch cần đối soát. Cấu hình Test mode và Live riêng. [Mẫu mã SePay](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan) · [Bộ lọc webhook](https://developer.sepay.vn/vi/sepay-webhooks/tao-webhook)

## Thẻ sinh viên

Có thể kết nối thẻ trước hoặc sau khi tạo hóa đơn. Nhấn **Tiến hành thanh toán**, chờ ECC với backend, nhập PIN 6 chữ số. Backend kiểm tra thẻ hợp lệ, ngữ cảnh đúng hóa đơn/kết nối và số dư thực đủ trả trước khi debit; không dựa vào số dư cache của desktop.

Mỗi hóa đơn yêu cầu xác thực riêng. Không phải rút/cắm lại thẻ để dùng hóa đơn tiếp theo. PIN sai/thiếu tiền vẫn hủy hóa đơn theo chính sách hiện có; bộ đếm sai PIN của thẻ không reset theo đơn.

Khi thẻ đã bắt đầu xử lý tiền, QR tạm ẩn và app yêu cầu chờ. Nếu chưa rõ kết quả, giữ/kết nối lại đúng thẻ để khôi phục; không thử thanh toán lần hai. `CANCEL_PENDING` chưa có nghĩa là đã hoàn tiền lên thẻ.

## Khoản chuyển không khớp hoặc trùng

Một giao dịch webhook gửi lại nhiều lần chỉ có một tác động. Khách chủ động chuyển hai giao dịch thật là hai khoản tiền khác nhau: hóa đơn chỉ ghi một lần thành công, khoản thêm đi đối soát.

Nếu ngân hàng thắng khi đang chờ PIN, hộp PIN đóng và không debit thẻ. Nếu khoản ngân hàng tới khi debit đang chạy, lưu chờ kết quả thẻ; sau đó đưa khoản thêm hoặc khoản của hóa đơn bị hủy vào đối soát. Khoản thiếu/thừa/đến muộn không tự biến hóa đơn đã hủy thành thành công.

Admin xem **Chuyển khoản hóa đơn cần đối chiếu** tại quầy quản lý thẻ. Bảng có số hóa đơn, mã, tiền cần trả/đã nhận, trạng thái/lý do, ID SePay và thời gian nhận. Đây là tra cứu chứng từ; chưa có lệnh tự chuyển khoản hoàn tiền. Chứng từ và mã tham chiếu tối thiểu còn giữ khi đăng ký thẻ/hóa đơn liên quan bị xóa.

## Nâng cấp và kiểm chứng

- Nâng backend và desktop cùng phiên bản; client cũ không hiểu đầy đủ danh mục, giai đoạn thanh toán và QR ngân hàng mới.
- Giữ nguyên migration V1–V8. V9 thêm 8 số yêu cầu vào mã TT/NT + MSSV, tra đúng toàn bộ mã và giữ unique index. WAITING_BANK dùng QR cũ hết hiệu lực; tạo phiếu/QR mới sau nâng cấp. BANK_RECEIVED/CREDITED và chứng từ đã có được giữ.
- Backup CSDL ứng dụng trước khi nâng cấp; dừng quầy/worker cũ trước khi áp dụng migration. Kiểm chứng nâng cấp trên DB test/bản sao trước. Chạy lại JAR cũ với schema mới không được coi là rollback đã kiểm chứng.
- Các test PostgreSQL chỉ được chạy trên `ttm_test` có chốt URL. Webhook mô phỏng và jCardSim chứng minh logic phần mềm trong phạm vi đó, không thay cho một giao dịch ngân hàng Live hay kiểm tra thẻ vật lý.
- Ảnh món và danh mục chỉ lưu ở backend. Không sửa applet/CAP, không thêm dữ liệu ảnh món vào RAM/EEPROM thẻ.

Kế hoạch và ma trận nghiệm thu: [kế hoạch triển khai](KE-HOACH-DANH-MUC-MON-VA-THANH-TOAN-HAI-PHUONG-THUC.md).


Mỗi hóa đơn/phiếu nạp mới có mã riêng. Cùng sinh viên có thể có hóa đơn ở các quầy khác nhau mà không trùng mã; không cần chờ sang giây kế tiếp. Cùng phiếu nạp đang chờ/cùng số tiền vẫn trả lại phiếu hiện có. QR của yêu cầu cũ chỉ trỏ về yêu cầu cũ, không fallback sang yêu cầu đang mở. Webhook đến muộn vẫn trả HTTP 200 để xác nhận đã nhận thông báo, nhưng không cộng tiền/PAID nếu yêu cầu đã hết hạn hoặc đã đóng. Xem [quy tắc mã và cấu hình](MA-CHUYEN-KHOAN-TT-NT.md).
