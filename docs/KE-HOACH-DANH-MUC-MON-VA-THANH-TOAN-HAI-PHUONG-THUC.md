# Kế hoạch quản lý danh mục, món và thanh toán hóa đơn bằng QR hoặc thẻ

Ngày lập: 06/11/2026. Phạm vi: backend PostgreSQL/Spring Boot, API dùng chung và ứng dụng Swing tại quầy căng tin.

Kế hoạch đã được triển khai trong mã nguồn ngày 07/11/2026: danh mục/món/ảnh, V6/V7, QR ngân hàng cho hóa đơn, thanh toán thẻ kết nối sẵn, chống lặp và đối soát. Điều chỉnh ngày 07/11 theo yêu cầu mới: MSSV đầy đủ bắt buộc; V8 bổ sung mã TT/NT + MSSV; V9 thêm 8 số yêu cầu duy nhất và tra đúng toàn bộ mã. Các phần mô tả V5/V7 bên dưới là mốc thiết kế trước thay đổi này; quy tắc hiện hành xem [Mã chuyển khoản TT/NT](MA-CHUYEN-KHOAN-TT-NT.md). Migration và nghiệp vụ đã kiểm thử trên PostgreSQL ttm_test; giao diện có ảnh fixture ở 960×660 và 1280×850. Chưa áp dụng vào CSDL ứng dụng và chưa sửa SePay Live. Hướng dẫn cuối cùng: [Quầy thực đơn và thanh toán](QUAY-THUC-DON-VA-THANH-TOAN.md).

## 1. Yêu cầu và quyết định nghiệp vụ

### 1.1. Những điểm người dùng đã chốt

- Nhân viên quầy được thêm/xóa danh mục, thêm/sửa/xóa món trực tiếp, **không cần đăng nhập admin**.
- Thêm danh mục bằng tên; chọn danh mục trên màn hình bán hàng để lọc các món thuộc danh mục đó.
- Xóa danh mục là xóa cứng cả danh mục và toàn bộ món trong danh mục. Trước khi xóa, hiển thị đúng câu: **“Danh mục này hiện có xx món! Tiếp tục xoá?”**, trong đó `xx` là số món thực tế do backend trả về.
- Thêm món gồm danh mục, tên, ảnh và giá. File ảnh phải có kích thước **đúng 256 × 256 pixel**, là ảnh vuông.
- Sửa món được đổi danh mục, tên và giá. Xóa món là xóa cứng.
- Nhấn **“Tạo hóa đơn”** sau khi chọn món sẽ hiển thị QR chuyển khoản tại tab **“Màn hình QR cho khách”**. Dùng cùng ngân hàng/tài khoản nhận tiền đã cấu hình cho nạp thẻ.
- Chuyển khoản hợp lệ được backend tự động ghi nhận thanh toán hóa đơn, không cần có thẻ tại đầu đọc.
- Có thể kết nối thẻ trước hoặc sau khi tạo hóa đơn. Nhấn **“Tiến hành thanh toán”** để bắt đầu xác thực thẻ và nhập PIN; backend phải xác thực, kiểm tra trạng thái thẻ và số dư trước khi trừ tiền.
- Màn hình cho khách có QR bên trái, chi tiết hóa đơn bên phải và nút thanh toán bằng thẻ.

### 1.2. Mặc định đề xuất cho các điểm chưa được chốt riêng

| Điểm | Đề xuất dùng khi triển khai | Lý do |
| --- | --- | --- |
| MSSV lúc tạo hóa đơn | Bắt buộc toàn bộ MSSV AT/CT/DT + sáu số | Dùng mã TT + MSSV theo yêu cầu mới; sinh viên chuyển khoản không cần kết nối thẻ. |
| Gắn sinh viên vào hóa đơn | Gắn từ thẻ đã được backend xác thực; nếu nhân viên đã nhập MSSV thì thẻ phải khớp | Không tin thông tin do giao diện tự nhận là đã xác thực. |
| Thời hạn hóa đơn | Giữ 5 phút như hiện tại | Không thay đổi thêm quy tắc đang dùng của quầy. |
| Tự động xác nhận chuyển khoản | Đúng mã hóa đơn, đúng tài khoản/VA, đúng số tiền, giao dịch tiền vào, còn điều kiện thanh toán | Không tự gộp nhiều khoản nhỏ hoặc tự suy đoán hóa đơn bằng số tiền. |
| Khoản thiếu/thừa, trùng, đến muộn | Lưu vào danh sách đối soát; không tự ghi đủ tiền, không tự chuyển tiền hoàn | Cần giữ chứng từ và phân biệt rõ đã nhận tiền với hóa đơn đã được thanh toán. |
| Sai PIN/thiếu số dư | Giữ quy tắc hiện tại: hủy lượt thanh toán/hóa đơn, tạo hóa đơn mới khi thử lại | Không thay đổi chính sách PIN và vòng đời hóa đơn ngoài yêu cầu này. Bộ đếm sai PIN của thẻ vẫn giữ theo thiết kế hiện tại. |
| Ảnh món | JPEG/PNG, đúng 256 × 256; đề xuất giới hạn file 1 MiB | Đủ cho ảnh món nhỏ; kiểm tra cả desktop và backend. Không tự cắt/thu ảnh sai kích thước để cho qua. |
| Sửa ảnh/đổi tên danh mục | Chưa đưa vào phạm vi lần này | Yêu cầu sửa món hiện gồm danh mục, tên và giá; danh mục hiện chỉ có thêm/xóa. |

Các mặc định trên cần được thể hiện rõ trong giao diện và kiểm thử. Nếu người dùng điều chỉnh, cập nhật phần chênh lệch trước khi triển khai phần phụ thuộc.

## 2. Hiện trạng và phần cần nối thêm

| Thành phần hiện có | Hiện trạng từ mã nguồn | Hướng xử lý |
| --- | --- | --- |
| `menu_items` | Danh mục là chuỗi `category`; chưa có bảng danh mục, ảnh và API quản lý món | Tạo bảng danh mục, liên kết bằng ID, bổ sung ảnh và phiên bản dữ liệu. |
| `invoice_lines` | Có bản sao tên/giá lúc bán, nhưng `item_id` bắt buộc và nằm trong khóa chính | Tách khóa dòng hóa đơn khỏi món nguồn để có thể xóa cứng món. |
| `WalletService.createInvoice` | Bắt buộc MSSV và thẻ ACTIVE ngay khi lập hóa đơn | Tách việc lập hóa đơn khỏi việc chọn thẻ trả tiền nếu áp dụng MSSV tùy chọn. |
| `InvoiceQr` | Tạo QR chứa `ttm://invoice/...`, chưa phải QR ngân hàng | Tạo VietQR cho mã chuyển khoản riêng của hóa đơn. |
| `SePayGateway` / `SePayTopups` | Đã xác thực webhook, chống lặp, ghép tiền nạp thẻ bằng `TTM` + 6 chữ số | Dùng chung cửa nhận tiền, phân loại rõ tiền nạp và tiền thanh toán hóa đơn. |
| `PaymentCoordinator` | Đã có debit, kết quả hóa đơn, khôi phục, reversal và release | Mở rộng quyền xử lý theo phương thức trước khi có tác động tiền trên thẻ. |
| `CheckoutView` | Có hai tab; tự mở PIN khi có kết nối mới sau tạo hóa đơn, từ chối kết nối đã có trước đó | Bỏ điều kiện kết nối mới; chuyển sang nút thanh toán chủ động. |
| `ProfileForm` / `Portraits` | Đã có chọn file, xem trước, xử lý ảnh ngoài EDT; ảnh chân dung được chuyển thành 480 × 640 | Tái sử dụng cách chọn/xem trước và chạy nền; tạo bộ kiểm tra ảnh món riêng. |
| Kiểm soát API | Có `X-Device-Key`, terminal/session và kiểm soát phiên quầy | API quản lý món dùng quyền thiết bị và phiên quầy, không yêu cầu bearer admin. |
| Flyway | Mã nguồn hiện có V1 đến V5 | Dự kiến thêm V6 cho thực đơn và V7 cho thanh toán; kiểm tra lại số phiên bản khi bắt đầu code. |

Không cần sửa applet hoặc lưu ảnh món/danh mục lên thẻ. Thẻ tiếp tục giữ vai trò xác thực và ví; thực đơn, ảnh món, hóa đơn và ngân hàng do backend quản lý. Phạm vi này không làm tăng ngân sách RAM/EEPROM đã tối ưu cho applet.

## 3. Giao diện và hành vi tại quầy

### 3.1. Tab nhân viên bán hàng

- Giữ khu chọn món và giỏ hàng hiện tại; phía trên danh sách món có **Danh mục: [Tất cả ▾]**, ô tìm tên và các nút **Thêm danh mục**, **Xóa danh mục**, **Thêm món**.
- `Tất cả` là bộ lọc tổng hợp, không phải bản ghi trong CSDL; không thể xóa. Khi chọn một danh mục, tìm kiếm chỉ áp dụng trong danh mục đó.
- Mỗi món hiển thị ảnh, tên và giá. Bấm phần chọn món để thêm vào giỏ; thao tác **Sửa/Xóa** đặt riêng để không vô tình thêm món khi đang quản lý.
- Thêm món khi đang chọn một danh mục sẽ điền sẵn danh mục đó; vẫn cho đổi trước khi lưu. Ở `Tất cả`, nhân viên phải chọn một danh mục thật.
- Form thêm món: danh mục, tên, **Chọn ảnh**, ảnh xem trước, thông tin `256 × 256`, giá VNĐ, **Tạo món**, **Hủy**. Báo lỗi ngay tại trường và giữ nội dung đã nhập nếu backend chưa lưu được.
- Form sửa món: danh mục, tên, giá; hiển thị ảnh hiện có để nhận biết. Không thêm tính năng sửa ảnh vào đợt này.
- Tên danh mục đề xuất 1–50 ký tự; tên món 1–120 ký tự sau khi bỏ khoảng trắng thừa. Danh mục không trùng tên sau chuẩn hóa chữ hoa/thường; vẫn giữ đúng dấu tiếng Việt.
- Giá là số nguyên VNĐ lớn hơn 0, không nhận số thập phân/âm. Dùng `long` và giữ giới hạn tổng hóa đơn 100.000.000 VNĐ hiện tại; giá một món không vượt giới hạn này.
- Danh mục trống hiển thị **“Danh mục này chưa có món”** cùng nút thêm món. Không có kết quả tìm kiếm thì giữ bộ lọc và cho xóa nội dung tìm kiếm.

### 3.2. Xóa cứng có xác nhận đúng phạm vi

1. Nhân viên chọn danh mục rồi nhấn **Xóa danh mục**.
2. Desktop lấy số món và phiên bản danh mục từ backend; không tự dùng số nút món đang hiển thị vì bộ lọc tìm kiếm có thể che bớt món.
3. Hộp thoại hiển thị tên danh mục, câu **“Danh mục này hiện có 12 món! Tiếp tục xoá?”** và giải thích các món/ảnh của danh mục sẽ bị xóa vĩnh viễn.
4. Hai lựa chọn **Hủy** / **Xóa danh mục và món**; mặc định focus ở Hủy.
5. Backend kiểm tra lại phiên bản trong transaction. Nếu món đã được thêm/chuyển/sửa sau lúc xem xác nhận, trả xung đột và yêu cầu xem số liệu mới trước khi xác nhận lại.
6. Xóa toàn bộ danh mục, món và ảnh trong một transaction. Thành công thì cập nhật bộ lọc, giỏ chưa tạo hóa đơn và thông báo kết quả.

Xóa món có hộp thoại nêu tên món và hành động xóa vĩnh viễn. Không dùng `available=false` hoặc `deleted_at` thay cho xóa cứng. Hóa đơn đã lập giữ nguyên các dòng đã chốt; giỏ chưa lập hóa đơn phải loại món đã xóa hoặc yêu cầu nhân viên cập nhật trước khi tạo.

### 3.3. Tab “Màn hình QR cho khách”

```text
┌──────────────────────────────────────────────────────────────────┐
│ HD00000123                 Chờ thanh toán               04:32    │
├──────────────────────────────┬───────────────────────────────────┤
│ CHUYỂN KHOẢN                 │ CHI TIẾT HÓA ĐƠN                  │
│                              │ Món            SL     Thành tiền │
│          [VietQR]            │ Cơm gà          2        70.000 đ │
│                              │ Nước suối       1        10.000 đ │
│ Ngân hàng / tài khoản nhận    │                                   │
│ Số tiền: 80.000 đ             │ Tổng cộng:               80.000 đ │
│ Nội dung: TTAT01020300000125  │                                   │
│                              │ THANH TOÁN BẰNG THẺ               │
│ Tự cập nhật khi nhận tiền     │ Trạng thái kết nối / hướng dẫn    │
│                              │ [ Tiến hành thanh toán ]          │
└──────────────────────────────┴───────────────────────────────────┘
```

- **Tạo hóa đơn** chốt tên/giá/số lượng từ backend rồi chuyển tới tab này. Giỏ không tiếp tục làm thay đổi hóa đơn đã tạo.
- QR và các thông tin số tiền/nội dung/tài khoản đều lấy từ cùng kết quả backend. Không hiển thị QR nội bộ `ttm://invoice` dưới nhãn chuyển khoản.
- Thẻ kết nối từ trước được dùng ngay khi nhấn nút; không yêu cầu rút/cắm lại. Kết nối thẻ sau khi tạo hóa đơn cũng dùng cùng quy trình.
- Nút **Tiến hành thanh toán** bắt đầu ECC → PIN → xác thực và kiểm tra tiền ở backend → debit. Không tự bật PIN chỉ vì vừa kết nối đầu đọc; không tái dùng PIN của hóa đơn trước hoặc phiên quản lý thẻ.
- Chưa có thẻ, thẻ chưa đăng ký/bị khóa, đang xử lý, đã hết hạn hoặc đã kết thúc: nút có trạng thái và lời giải thích tương ứng. Nếu chưa xác thực PIN thì chưa hiển thị số dư như một kết quả đã được kiểm chứng.
- Khi backend xác nhận thanh toán thành công: bỏ QR, đóng hộp PIN còn mở, hiển thị **Đã thanh toán bằng chuyển khoản/thẻ**, số hóa đơn và tổng tiền.
- Trong giai đoạn ECC/nhập PIN chưa giữ quyền debit, QR vẫn có thể dùng. Khi backend đã chuyển sang `CARD_PROCESSING`, tạm ẩn QR và báo **“Đang xử lý thanh toán bằng thẻ”** để khách không chủ động chuyển thêm; khoản đã chuyển từ trước vẫn được tiếp nhận và xử lý theo mục 5.3.
- Khi mất mạng/chưa rõ debit: hiển thị đang xác định kết quả; không cho tạo tác động tiền lần hai để “thử lại”.
- Giữ polling hóa đơn kể cả khi hộp PIN đang mở. Không để cờ bận chung của giao diện chặn mọi cập nhật kết quả ngân hàng. Phản hồi cũ phải bị loại bằng ID hóa đơn, phiên quầy, `revision` và thế hệ màn hình như mô hình hiện có.
- Dùng Swing/FlatLaf, bảng, dialog, màu và định dạng tiền hiện có. Kiểm tra kích thước tối thiểu 960 × 660 và kích thước mặc định tối đa 1280 × 850 đang dùng; phần chi tiết hóa đơn cuộn khi dài, QR giữ tỷ lệ và khoảng trắng để quét được. Không đổi QR/phương thức khi chỉ co giãn cửa sổ.
- Các nút/form dùng được bằng bàn phím; label gắn với ô nhập, focus quay về chỗ hợp lý sau khi đóng dialog. Lỗi và kết quả phải có chữ, không chỉ đổi màu.

## 4. Thiết kế dữ liệu và API

### 4.1. Migration V6: danh mục, ảnh và dòng hóa đơn

| Thành phần | Thay đổi dự kiến |
| --- | --- |
| `menu_categories` mới | `id`, `name`, tên chuẩn hóa duy nhất, `revision`, thời gian tạo/cập nhật. `revision` tăng khi thay đổi món trong danh mục để chống xác nhận xóa bằng dữ liệu cũ. |
| `menu_items` | Thêm `category_id`, ảnh `BYTEA`, MIME, phiên bản món/ảnh; FK tới danh mục có `ON DELETE CASCADE`. Dữ liệu ảnh đi cùng bản ghi món để xóa nguyên tử, không để file mồ côi. |
| Dữ liệu cũ | Tạo danh mục từ các chuỗi hiện có, gắn lại đúng các món, giữ ID/giá. Món có sẵn chưa có ảnh dùng ảnh thay thế trong desktop; chỉ bắt buộc ảnh cho món tạo mới. |
| `invoice_lines` | Thêm khóa dòng độc lập hoặc số thứ tự dòng; bỏ phụ thuộc khóa chính vào `item_id`. Cho `item_id` nullable, FK `ON DELETE SET NULL`. Giữ bản sao tên, giá, số lượng; thêm tên danh mục lúc bán nếu cần hiển thị lịch sử. |
| Tra cứu/phiên bản | Index theo danh mục; phiên bản món hỗ trợ phát hiện giỏ cũ và sửa đồng thời. Trường category dạng chữ trong DTO cũ có thể tính từ danh mục để tương thích. |

**Bất biến:** xóa danh mục chỉ cascade xuống dữ liệu thực đơn/ảnh; tuyệt đối không cascade xuống `invoice_lines`, `invoices`, thanh toán hoặc giao dịch ngân hàng. Ví dụ xóa món “Cơm gà” sau khi đã bán vẫn đọc được hóa đơn “Cơm gà, 2 phần, 35.000 đ/phần”.

Các thao tác thêm/chuyển/sửa/xóa món khóa danh mục liên quan theo ID tăng dần; cập nhật revision trong cùng transaction. Xóa danh mục so sánh `expectedRevision` và số món đã xem. Vì revision thay đổi theo thành viên, đổi một món ra rồi một món vào dù tổng số vẫn bằng nhau cũng không lọt xác nhận cũ.

Tạo hóa đơn đọc/chốt các món và giá bằng transaction có kiểm soát đồng thời với sửa/xóa. Nếu món hoặc giá đã đổi so với giỏ nhân viên vừa xem, trả lỗi xung đột kèm dữ liệu mới để xem lại tổng tiền; không âm thầm tạo hóa đơn có giá khác màn hình.

### 4.2. Migration V7: hóa đơn có hai phương thức thanh toán

- Thêm `payment_method` (`CARD`/`BANK_TRANSFER`), quyền xử lý thanh toán và phiên bản/quyền của lượt thẻ. Giữ `status`/`outcome` hóa đơn hiện có làm kết quả cuối, tránh hai nguồn trạng thái mâu thuẫn.
- Phân biệt giai đoạn xác thực thẻ chưa tác động tiền với giai đoạn đã giữ quyền debit; các nhãn như `CARD_AUTH` và `CARD_PROCESSING` là giai đoạn của thanh toán, không nhất thiết thêm mọi nhãn vào `invoices.status`.
- V7 từng cho phép MSSV tùy chọn. V8 yêu cầu MSSV đầy đủ khi tạo để sinh TT + MSSV; backend tra sinh viên đã đăng ký. Luồng thẻ vẫn cần xác thực ECC/PIN cho đúng hóa đơn/kết nối.
- Tạo bảng tham chiếu chuyển khoản hóa đơn, ví dụ `invoice_payment_references`: mã duy nhất, liên kết hóa đơn, số hóa đơn, số tiền, thời điểm tạo/hết hạn và kết quả cuối tối thiểu. Mã được cấp một lần, không tái sử dụng sau hủy hoặc xóa dữ liệu thẻ.
- Mã public hiện hành V9: **TT/NT + MSSV + 8 số yêu cầu**; ví dụ TTAT01020300000125 và NTCT07035200000842. ID nội bộ V7 vẫn giữ riêng. Mỗi yêu cầu có mã duy nhất, retry/tải QR giữ mã; không cần tái sử dụng cửa sổ theo sinh viên.
- Mở rộng `bank_events` thay vì tạo một cửa nhận tiền độc lập: tham chiếu hóa đơn, loại đích (`TOPUP`/`INVOICE`/không xác định), kết quả xử lý, thời gian giao dịch. Mở rộng độ dài `payment_code` hiện chỉ 9 ký tự.
- Một giao dịch chỉ có một đích sử dụng tiền. Ràng buộc CSDL không cho cùng biên nhận vừa cấp tiền nạp thẻ vừa trả hóa đơn, hoặc cùng hóa đơn có hai lần xác nhận thành công. Giữ khóa chống lặp theo giao dịch SePay và tham chiếu ngân hàng hiện có.
- Có bản ghi đối soát tiền hóa đơn riêng, tham chiếu biên nhận ngân hàng duy nhất, số tiền, lý do và kết quả xử lý. Không nhét vào `refund_cases` hiện gắn với một thẻ/topup rồi làm sai ý nghĩa dữ liệu.
- Cập nhật đường xóa thẻ: hiện `CardService` có xóa các hóa đơn của thẻ. Trước khi xóa, giữ tham chiếu/biên nhận ngân hàng tối thiểu tách khỏi thẻ; FK từ tham chiếu tới hóa đơn dùng `SET NULL` khi phù hợp. Mã cũ không thể trả tiền cho hóa đơn/thẻ thay thế. Không tự mở rộng lưu thông tin cá nhân ngoài chính sách hiện tại.

### 4.3. API dự kiến

Tất cả đường dưới đây nằm dưới `/api`, trừ webhook. Giữ cách gọi mutation POST hiện có để không buộc đổi framework/client.

| API | Mục đích |
| --- | --- |
| `GET /menu/categories` | Danh mục, số món và revision. |
| `GET /menu?categoryId=...&query=...` | Thực đơn có bộ lọc; không truyền tham số vẫn lấy thực đơn như hiện tại. |
| `GET /menu/items/{id}/image` | Lấy ảnh với MIME/version; danh sách món không nhúng tất cả ảnh base64. |
| `POST /menu/categories` | Tạo danh mục từ tên. |
| `GET /menu/categories/{id}/delete-preview` | Lấy tên, số món thực tế và revision để xác nhận. |
| `POST /menu/categories/{id}/delete` | Xóa cứng với revision đã xem; trả 409 nếu dữ liệu đã đổi. |
| `POST /menu/items` | Tạo món với tên, danh mục, giá, ảnh. |
| `POST /menu/items/{id}/update` | Sửa danh mục/tên/giá với revision. |
| `POST /menu/items/{id}/delete` | Xóa cứng món với revision. |
| `POST /invoices` | Chốt hóa đơn, cấp mã chuyển khoản và dữ liệu QR; thêm `requestId` chống tạo lặp khi mất phản hồi. |
| `GET /invoices/{id}` | Kết quả, phương thức, giai đoạn thanh toán và revision; giữ vai trò nguồn trạng thái cho desktop. |
| `POST /invoices/{id}/prepare`, `/pay`, `/end-auth`, `/cancel` | Điều chỉnh luồng hiện có để nhận thẻ trước/sau tạo hóa đơn và cùng tuân thủ quyền xử lý hóa đơn. |
| `POST /webhook` | Dùng chung endpoint SePay hiện có; lưu/định tuyến biên nhận rồi phản hồi theo hợp đồng hiện tại. |

Không đặt API quản lý món dưới quyền `/admin`: mutation yêu cầu `X-Device-Key` hợp lệ và phiên quầy đang hoạt động. Ghi audit theo quầy/phiên và hành động; không ghi giả danh một nhân viên cụ thể khi hệ thống không có đăng nhập nhân viên. Kiểm thử cả gọi API trực tiếp, không chỉ việc ẩn/hiện nút.

Các DTO mới nên thêm ID danh mục, image version/URL, revision, phương thức và mã chuyển khoản theo hướng bổ sung. Khi bắt buộc thay đổi hợp đồng tạo hóa đơn hoặc nullable identity, triển khai desktop/backend cùng phiên bản; không tuyên bố client cũ tự hỗ trợ được toàn bộ luồng mới.

Các lệnh tạo danh mục/món có mã yêu cầu để retry sau mất phản hồi không tạo thêm bản ghi. Lệnh sửa/xóa dùng ID và revision; desktop có cờ chặn bấm lặp trong lúc gửi. Dữ liệu giỏ gửi kèm phiên bản món/giá đã xem để backend phát hiện thay đổi trước khi chốt hóa đơn.

### 4.4. Kiểm tra và lưu ảnh

- Tái sử dụng trải nghiệm `JFileChooser` + `SwingWorker` của ảnh đại diện, không gọi `Portraits.prepare` vì hàm đó cắt/đổi ảnh thành 480 × 640.
- Desktop kiểm tra loại file thực, dung lượng, kích thước; thông báo **“Chỉ chấp nhận ảnh JPEG/PNG kích thước đúng 256 × 256 pixel.”** nếu sai. Chọn ảnh sai không làm mất ảnh đang xem hoặc thông tin form.
- Backend kiểm tra lại bằng bộ giải mã ảnh, đọc kích thước trước khi giải mã toàn bộ, giới hạn request/file và chỉ nhận JPEG/PNG; không tin phần mở rộng hoặc MIME từ client.
- Có thể mã hóa lại ảnh đúng kích thước để bỏ metadata; không biến ảnh 512 × 512 hoặc ảnh chữ nhật thành ảnh hợp lệ bằng cách thu/cắt ngầm.
- Ảnh lấy/cập nhật ngoài EDT, cache theo ID + image version với số tác vụ tải giới hạn. Lỗi tải ảnh dùng ảnh thay thế và không chặn chọn món hoặc thanh toán.

## 5. Luồng ngân hàng và thẻ dùng chung một kết quả hóa đơn

### 5.1. Chuyển khoản tự động

1. Tạo hóa đơn và tham chiếu chuyển khoản trong cùng transaction; retry cùng `requestId` trả lại cùng hóa đơn/mã.
2. Dùng `SePayGateway` tạo VietQR với **cấu hình ngân hàng/tài khoản/VA hiện có**, tổng tiền và mã hóa đơn riêng. QR tạo lỗi thì cho tải lại cùng mã trong thời hạn; không tạo hóa đơn mới hoặc giả báo đã có QR.
3. SePay gọi webhook; xác thực và kiểm tra tài khoản, tiền vào, số tiền, thời gian, mã. Parse nội dung gốc và kiểm tra trường `code` nếu có; nhiều mã, mã topup lẫn mã hóa đơn hoặc code/content mâu thuẫn thì không đoán đích nhận tiền.
4. Lưu biên nhận có khóa duy nhất trong PostgreSQL rồi định tuyến đúng một lần: `NT` + MSSV tới luồng nạp, `TT` + MSSV tới hóa đơn. Request giả thử `id=0` chỉ kiểm tra kết nối, không phát sinh tiền như cách hiện tại.
5. Nếu hóa đơn còn được phép thanh toán và chưa có quyền debit đang chạy, chốt `PAID`, `payment_method=BANK_TRANSFER`, biên nhận và revision trong một transaction.
6. Giao diện polling nhận kết quả và chuyển sang màn hình thành công. Chuyển khoản này **không tạo topup, không cộng/trừ ví thẻ và không yêu cầu PIN**.

Chỉ trả xác nhận webhook sau khi biên nhận đã lưu bền vững. Phần nhận tiền không chờ APDU hoặc hoàn tác thẻ; nếu chưa thể kết luận thì lưu trạng thái chờ và có tác vụ khôi phục/đối soát sau restart. Giữ hợp đồng phản hồi hiện có `{"success":true}`; lỗi lưu DB không được trả thành công giả.

### 5.2. Thanh toán bằng thẻ

1. Nhân viên tạo hóa đơn rồi khách/nhân viên bấm **Tiến hành thanh toán**, dù thẻ đã kết nối trước đó hay mới kết nối.
2. Backend kiểm tra phiên quầy sở hữu hóa đơn, hạn, kết nối thẻ, thẻ đã đăng ký/ACTIVE và không bị khóa; thực hiện xác thực ECC.
3. Nhập PIN 6 chữ số. Backend xác thực PIN cho **đúng hóa đơn, đúng connection**, giữ chống lặp lần thử PIN. Thay thẻ hoặc đổi connection làm vô hiệu ngữ cảnh cũ.
4. Đọc/đồng bộ trạng thái ví thực sau khi được xác thực. Kiểm tra `balance >= total` ở backend và giữ kiểm tra chống âm trong applet; số dư cache trên desktop chỉ hỗ trợ hiển thị.
5. Trước khi tạo/gửi lệnh tiền, backend chiếm quyền `CARD_PROCESSING` bền vững dưới khóa hóa đơn và kiểm tra lại toàn bộ điều kiện. Nếu ngân hàng đã chốt thành công thì dừng, không debit.
6. Tiếp tục giao thức `PREPARE → COMMIT → ACK`, outcome, `RELEASE`/`REVERSAL` hiện có. Chỉ chốt `PAID/CARD` theo chứng từ đã đồng bộ; mất phản hồi phải truy vấn kết quả, không trừ lại.

Thẻ có thể được kết nối sớm, nhưng không vì vậy mà bỏ ECC hoặc PIN của lượt hóa đơn này. Lỗi chưa rõ tác động tiền giữ trạng thái chờ khôi phục; không báo “chưa trừ” khi chưa chứng minh được.

### 5.3. Khi hai phương thức xảy ra gần nhau

Chỉ thêm câu `if PAID` cuối luồng là chưa đủ: thẻ có thể đã bị trừ trước khi webhook tới. Cần quyền xử lý được lưu **trước** APDU tiền, cùng cổng quyết định cho webhook, thẻ, hết hạn và hủy.

| Tình huống | Quyết định |
| --- | --- |
| Đang OPEN hoặc mới ECC/nhập PIN, chưa giữ quyền debit | Ngân hàng có thể thắng bằng transaction. Đóng PIN trên UI; mọi bước thẻ sau đó kiểm tra lại quyền và dừng. |
| Thẻ đã giữ quyền `CARD_PROCESSING`, chưa rõ tiền đã trừ hay chưa | Webhook vẫn lưu biên nhận một lần với trạng thái chờ kết quả thẻ. Không tự ghi PAID lần hai, không chuyển tiền này thành nạp thẻ. |
| Thẻ thành công và có thêm khoản chuyển khoản thật | Hóa đơn chỉ có một kết quả PAID/CARD; khoản chuyển thêm có hồ sơ đối soát riêng, hiển thị cho người có quyền xử lý. |
| Thẻ thất bại/hủy, có khoản chuyển đến trong lúc đó | Chờ chứng minh không còn debit ròng hoặc hoàn tác xong. Theo mặc định giữ quy tắc hủy hóa đơn hiện tại, khoản ngân hàng vào đối soát; không tự hồi sinh hóa đơn đã có outcome CANCEL. |
| Một webhook gửi lại nhiều lần | Cùng ID giao dịch chỉ có một biên nhận và một tác động; replay không tạo thêm khoản đối soát. |
| Khách chuyển hai giao dịch thật cho cùng mã | Giao dịch hợp lệ đầu có thể thanh toán; khoản thực nhận thứ hai là tiền dư cần đối soát. |
| Hủy/hết hạn/đóng quầy chạy cùng lúc webhook | Cùng khóa và kiểm tra outcome. Nếu thành công đã chốt thì không bị hủy; nếu CANCEL đã chốt thì khoản đến chuyển đối soát. |

Khóa theo thứ tự thống nhất với các đường hiện tại (phiên quầy → thẻ nếu cần → hóa đơn → lượt thanh toán/ví); chỉ dùng transaction ngắn để giữ quyền/chốt kết quả, không giữ SQL lock trong lúc chờ khách nhập PIN hay APDU. Không cho quyền debit hết hạn rồi tự nhường cho ngân hàng trong khi chưa rõ tác động trên thẻ. Khôi phục sau restart phải xử lý được cả biên nhận ngân hàng đang chờ và lượt debit chưa kết luận.

Điều bảo đảm là một hóa đơn chỉ có một kết quả thanh toán thành công và không bị hệ thống trừ thẻ thêm khi ngân hàng đã thắng. Không thể ngăn khách tự chuyển thêm tiền từ app ngân hàng; hệ thống phải phát hiện, giữ chứng từ và báo khoản dư để xử lý.

### 5.4. Hết hạn, tiền không khớp và vận hành

- So thời điểm giao dịch theo dữ liệu ngân hàng và cửa sổ của mã; không dùng riêng thời điểm nhận webhook để đoán đơn. Đồng thời tôn trọng outcome đã chốt và hạn hóa đơn của backend.
- Tiền tới sau khi hóa đơn đã hủy/hết hạn/đóng phiên không tự kích hoạt lại hóa đơn. Kể cả giao dịch ngân hàng thực hiện sớm nhưng webhook đến sau khi CANCEL đã chốt, giữ bằng chứng để đối soát.
- Thiếu/thừa tiền không tự ghi PAID; không tự gộp nhiều lần chuyển hoặc phân bổ tiền dư cho hóa đơn tiếp theo trong đợt này.
- Bổ sung tra cứu tối thiểu tiền hóa đơn cần đối soát vào khu vực quản trị đang có; giữ quyền xử lý/ghi nhận hoàn tiền theo quyền hiện tại. Quyền quản lý thực đơn trực tiếp của nhân viên không đồng nghĩa có quyền xác nhận hoàn tiền ngân hàng.
- Đối soát cần thấy mã hóa đơn, số tiền cần trả/đã nhận, ID giao dịch, lý do và trạng thái xử lý; không hiển thị khóa webhook hay dữ liệu nhạy cảm không phục vụ xử lý.

## 6. Thứ tự triển khai

| Bước | Công việc cụ thể | Điều kiện hoàn tất |
| --- | --- | --- |
| 1. CSDL và API thực đơn | V6, DTO, `MenuService`, API category/item/image; validate, revision và xóa cứng | Chạy được cả dữ liệu mới và dữ liệu đã có hóa đơn; xóa món không mất dòng hóa đơn. |
| 2. UI quản lý thực đơn | Bộ lọc, ảnh món, form thêm/sửa, xác nhận xóa, đồng bộ giỏ | Nhân viên thao tác trực tiếp, đủ kiểm tra ảnh đúng 256 × 256, không cần admin. |
| 3. Hợp đồng hóa đơn và V7 | MSSV đầy đủ theo V8, requestId, mã chuyển khoản, trường phương thức/quyền xử lý | Tạo hóa đơn không cần thẻ cho chuyển khoản; retry không tạo hóa đơn/mã thứ hai. |
| 4. Cổng quyết định thanh toán | Mở rộng `PaymentCoordinator`, `StationSessions`, các đường hủy/hết hạn; giữ giao thức thẻ | Ngân hàng/thẻ/hủy dùng cùng quy tắc, quyền giữ trước APDU, khôi phục được. |
| 5. SePay hóa đơn | Tách nhận biên nhận chung khỏi `SePayTopups`, parser hai loại mã, ghép hóa đơn, đối soát | Webhook nạp thẻ cũ không đổi tác động; một khoản tiền không đi vào hai luồng. |
| 6. Màn hình thanh toán | QR trái/chi tiết phải, nút Tiến hành thanh toán, cho thẻ kết nối trước, polling và trạng thái | Chuyển khoản tự cập nhật; thẻ vẫn ECC + PIN + số dư; không yêu cầu kết nối lại. |
| 7. Kiểm chứng và bàn giao | Test PostgreSQL `ttm_test`, HTTP, jCardSim, kiểm tra UI, tài liệu và gói triển khai | Có bằng chứng kiểm thử, backup/migration/runbook; ghi riêng phần chưa kiểm chứng ngân hàng/thẻ thật. |

Trong lúc chỉnh sửa chạy kiểm tra theo phần bị thay đổi; sau khi các phần hội tụ chạy bộ hồi quy liên quan một lượt. Không tự áp dụng migration vào `ttm_cantin`, không tự thay tài khoản hoặc bộ lọc SePay Live trong giai đoạn lập kế hoạch.

## 7. Ma trận kiểm chứng bắt buộc khi triển khai

| Nhóm | Ca kiểm chứng chính |
| --- | --- |
| Migration | DB mới; nâng V5 đã có danh mục dạng chữ, hóa đơn đã bán; giữ ID, giá, tổng và lịch sử; không sửa V1–V5. |
| Danh mục | Tạo tên hợp lệ/trống/trùng; lọc + tìm kiếm; danh mục 0 món; xóa đúng số món; preview cũ khi có thêm/chuyển món, kể cả tổng số không đổi. |
| Món và ảnh | Tạo đúng ảnh 256 × 256; từ chối 255 × 256, 512 × 512, file giả ảnh/hỏng/quá lớn; sửa danh mục/tên/giá; xóa cứng ảnh; món cũ dùng placeholder. |
| Giá và lịch sử | Sửa giá/xóa món khi đang có giỏ; tạo hóa đơn đồng thời với sửa/xóa; hóa đơn đã tạo vẫn giữ tên/giá/số lượng/tổng và thanh toán được sau xóa món. |
| Quyền API | Quầy hợp lệ không có bearer admin vẫn quản lý món được; thiếu/sai device key hoặc phiên quầy không hợp lệ bị chặn; không phụ thuộc việc ẩn nút. |
| QR ngân hàng | Đúng tài khoản/VA hiện cấu hình, đúng tổng, mã riêng; tạo lại không đổi mã; lỗi tải QR có retry; không cần có thẻ tại đầu đọc nhưng cần MSSV đầy đủ. |
| Webhook | Chữ ký/API key hiện có, đúng/sai tài khoản, tiền ra, amount sai, code null/mâu thuẫn/nhiều mã, topup và invoice lẫn nhau, timestamp, mock id=0, replay và hai request đồng thời. |
| Thẻ | Kết nối trước và sau tạo hóa đơn; ECC sai, thẻ bị khóa/chưa khởi tạo/khác MSSV nếu có; PIN sai, đủ/thiếu tiền; đúng bằng số dư; mỗi hóa đơn xác thực riêng. |
| Cạnh tranh hai phương thức | Webhook trong lúc nhập PIN; trước/sau giữ quyền debit; sau COMMIT nhưng trước khi chốt outcome; hai lần chuyển thật; hủy/expiry/đóng quầy đồng thời. Dùng điểm dừng kiểm thử xác định, không chỉ chạy ngẫu nhiên. |
| Khôi phục | Mất phản hồi sau thanh toán; mất kết nối thẻ; restart trước/sau debit; webhook đã lưu nhưng chưa xử lý; `CANCEL_PENDING`/REVERSAL/RELEASE; khoản đối soát không mất sau xóa thẻ. |
| UI | Hai cột tại 960 × 660 và kích thước mặc định; hóa đơn nhiều dòng; không ảnh/menu trống; bàn phím/dialog/focus; nút bấm lặp; QR không còn hiện sau thành công/hết hạn; PIN đóng khi ngân hàng thắng. |
| Hồi quy | Nạp thẻ SePay và ghép mã NT + MSSV; bộ đếm sai PIN; station lease/owner; payment/refund/delete hiện có; không thay đổi CAP/applicative APDU của applet. |

Dùng PostgreSQL thật trong DB test biệt lập để chứng minh khóa, constraint và race; unit test đơn thuần không đủ cho các điểm này. Dùng jCardSim cho hành vi thẻ theo phạm vi người dùng đã xác nhận chưa có thẻ thật. Test webhook mô phỏng không chứng minh giao dịch ngân hàng Live; cần ghi rõ giới hạn đó khi bàn giao.

## 8. Tập tin dự kiến tác động và cách bàn giao

- `shared/.../Api.java`: DTO danh mục/món, ảnh, revision, hóa đơn và phương thức; helper kiểm tra ảnh món riêng khi phù hợp.
- `backend/.../MenuService.java` mới và controller tương ứng: chuyển trách nhiệm quản lý thực đơn ra khỏi `WalletService` để không trộn với xử lý tiền.
- `backend/.../WalletService.java`, `InvoiceQr.java`, `PaymentCoordinator.java`, `StationSessions.java`: lập/đọc hóa đơn, QR, chọn phương thức, hủy và khôi phục.
- Cập nhật điều kiện hiển thị QR và điều kiện thanh toán theo giai đoạn xác thực/quyền debit; không giữ nguyên quy tắc hiện tại “chỉ status OPEN mới có QR” rồi làm mất lựa chọn chuyển khoản ngay khi mới mở PIN.
- `backend/.../SePayGateway.java`, `SePayTopups.java`, `SePayWebhookController.java`: xác thực/parse chung, chống lặp và định tuyến; bổ sung handler hóa đơn và lưu đối soát.
- `backend/.../CardService.java` và retention liên quan: bảo toàn dấu vết tiền hóa đơn khi thực hiện xóa thẻ theo chính sách hiện có.
- `backend/src/main/resources/db/migration/V6__menu_catalog.sql`, `V7__invoice_bank_payments.sql` dự kiến; không chỉnh sửa migration đã phát hành.
- `src/.../CheckoutView.java`, các dialog danh mục/món mới và `ApiClient.java`: UI bán hàng, ảnh, chọn thẻ, polling; giữ `CanteenFrame` làm khung chung.
- Các test thực đơn mới và test liên quan `InvoiceOutcomeTest`, `SePayGatewayTest`, `SePayFlowTest`, `SePayHttpFlowTest`, `HttpFlowTest`, migration/retention; bổ sung test UI logic khi có tác dụng phát hiện hồi quy.
- `docs/THIET-KE.md`, `docs/KIEM-CHUNG.md`, `docs/NAP-TIEN-SEPAY.md` và hướng dẫn quầy: cập nhật theo hành vi cuối cùng đã kiểm chứng.

Trước khi chạy với dữ liệu thật, tạo backup và kiểm chứng nâng cấp trên bản sao/DB test. Backend và desktop cần cùng phiên bản; rollback sau migration phải dùng bản ứng dụng tương thích hoặc khôi phục backup đã kiểm tra. Không dựa vào việc chạy file JAR cũ với schema mới để coi là đã rollback.

## 9. Cấu hình ngoài mã nguồn cần kiểm tra khi triển khai

Tạo VietQR có tham số tài khoản, ngân hàng, số tiền và nội dung; có thể dùng lại cách tạo QR của phần nạp thẻ với nội dung hóa đơn riêng. Quy tắc tài khoản/VA phải giữ theo cấu hình đang dùng, không thay số VA bằng số tài khoản chính chỉ vì thêm luồng hóa đơn. Tham khảo [tài liệu tạo VietQR của SePay](https://developer.sepay.vn/vi/tien-ich-khac/tao-qr-code).

SePay có thể gửi lại cùng giao dịch; cần lưu ID duy nhất và giữ phản hồi `success=true` theo hợp đồng webhook. Việc xác nhận đã nhận webhook tách khỏi việc đợi thao tác thẻ. Tham khảo [tài liệu tích hợp webhook](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook).

Khi chạy tích hợp, dùng sáu tiền tố TTAT/TTCT/TTDT/NTAT/NTCT/NTDT, hậu tố chỉ số đúng 14 ký tự (6 số MSSV + 8 số yêu cầu), và bộ lọc webhook nhận đủ sáu mẫu. Nếu chỉ sửa parser trong backend nhưng SePay đang lọc chỉ mã nạp, tiền hóa đơn có thể không được gửi về. Thử ở Test mode trước; không tự sửa Live trong lượt lập kế hoạch. Tham khảo [cấu hình mã thanh toán](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan) và [bộ lọc webhook](https://developer.sepay.vn/vi/sepay-webhooks/tao-webhook).

## 10. Tiêu chí nghiệm thu

1. Nhân viên quản lý đầy đủ danh mục/món tại quầy, không phải đăng nhập admin.
2. Ảnh món mới chỉ nhận đúng JPEG/PNG 256 × 256; ảnh không đi vào thẻ.
3. Xóa cứng danh mục/món có đúng xác nhận, nguyên tử, không làm mất hoặc đổi các dòng hóa đơn đã lập.
4. Một hóa đơn dùng được QR chuyển khoản hoặc thẻ; QR trái, chi tiết phải, nút **Tiến hành thanh toán** rõ ràng.
5. Chuyển khoản hợp lệ tự cập nhật thành công; thẻ kết nối trước/sau đều thanh toán được sau ECC/PIN và kiểm tra số dư ở backend.
6. Không có hai kết quả thành công cho một hóa đơn; replay không phát sinh tiền lần hai; khoản chuyển thực sự dư hoặc không khớp có chứng từ đối soát.
7. Nạp thẻ và cơ chế debit/hoàn tác/xóa thẻ hiện có vượt qua kiểm thử hồi quy; ghi rõ những gì mới chỉ được chứng minh bằng PostgreSQL test, webhook mô phỏng và jCardSim.


## Điều chỉnh TT/NT được người dùng chốt sau kế hoạch

V8 bổ sung TT/NT + MSSV; V9 hiện hành thêm 8 số hóa đơn/phiếu nạp để từng yêu cầu có mã riêng. MSSV bắt buộc và chỉ ghi tiền khi yêu cầu còn hiệu lực tại lúc xử lý. Webhook nghiệp vụ không hợp lệ vẫn trả HTTP 200 để xác nhận đã nhận, giữ chứng từ REVIEW và không cộng tiền/PAID. Tra toàn bộ mã 18 ký tự, không fallback theo MSSV; QR cũ chỉ trỏ về yêu cầu cũ. Không cần khoảng chờ sang giây kế tiếp. Xem [đặc tả hiện hành và cấu hình Dashboard](MA-CHUYEN-KHOAN-TT-NT.md).
