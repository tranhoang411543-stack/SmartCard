# SmartCard — Hệ thống quản lý căn tin bằng thẻ thông minh

SmartCard là project quản lý hoạt động căn tin bằng thẻ sinh viên. Nhân viên có thể quản lý thực đơn, tạo hóa đơn và thanh toán bằng số dư trên thẻ hoặc chuyển khoản qua QR. Ứng dụng gồm hai màn hình quầy, backend, cơ sở dữ liệu và applet Java Card.

## Chức năng chính

- Quản lý hồ sơ sinh viên và thẻ: khởi tạo, xác thực PIN, xem thông tin, khóa hoặc khôi phục thẻ.
- Quản lý ví trên thẻ: nạp tiền, xem lịch sử và đồng bộ số dư giữa thẻ với backend.
- Quản lý thực đơn và hóa đơn tại quầy thanh toán.
- Thanh toán hóa đơn bằng số dư thẻ hoặc chuyển khoản QR; tiếp nhận thông báo giao dịch từ SePay.
- Ghi nhận trạng thái thao tác để có thể tiếp tục đối soát khi thẻ, đầu đọc hoặc kết nối bị gián đoạn.

## Thành phần

| Thư mục | Vai trò |
|---|---|
| `card/` | Applet Java Card và mã dùng để đóng gói CAP. |
| `shared/` | Kiểu dữ liệu, định dạng hồ sơ và giao thức dùng chung. |
| `backend/` | API Spring Boot, xử lý tài khoản, ví, hóa đơn, cơ sở dữ liệu và giao tiếp với thẻ. |
| `src/` | Hai ứng dụng desktop Swing/FlatLaf cho quầy quản lý và quầy thanh toán. |
| `build/` | Maven parent POM điều phối các module. |
| `docker/`, `compose.yaml` | Cấu hình PostgreSQL chạy bằng Docker Compose. |
| `docs/` | Tài liệu thiết kế, cấu hình, vận hành và kết quả kiểm chứng. |

## Công nghệ

- Java 25, Maven và Spring Boot.
- Java Card applet; jCardSim được dùng để mô phỏng thẻ trong các luồng kiểm thử.
- Swing và FlatLaf cho giao diện desktop.
- PostgreSQL và Flyway cho lưu trữ dữ liệu và quản lý migration.
- Docker Compose cho môi trường PostgreSQL cục bộ.

## Build và chạy trên Windows

Cần cài JDK 25, Maven và Docker Desktop. Script `00-Build.cmd` hiện gọi Maven tại `C:\Program Files\apache-maven-3.9.12\bin\mvn.cmd`; nếu Maven trên máy nằm ở đường dẫn khác, cần cập nhật đường dẫn trong script trước khi build. Các script trong project là lối vào để chuẩn bị môi trường và mở ứng dụng:

```powershell
.\00-Build.cmd
.\02-Backend.ps1
.\03-Quay-quan-ly.cmd
.\04-Quay-thanh-toan.cmd
```

Nếu chưa có tài khoản quản trị, xem hướng dẫn khởi tạo tại [`10-Khoi-tao-admin.ps1`](10-Khoi-tao-admin.ps1). Cấu hình chạy cục bộ được đặt trong `.local/`; thư mục này bị loại khỏi Git. Hãy tạo cấu hình theo tài liệu trong [`docs/`](docs/) và không đưa mật khẩu, khóa webhook, thông tin ngân hàng hoặc dữ liệu cá nhân vào repository.

## Tài liệu liên quan

- [Thiết kế hệ thống và giao thức thẻ](docs/THIET-KE.md)
- [Cấu hình PostgreSQL bằng Docker](docs/CSDL-DOCKER.md)
- [Nạp tiền và webhook SePay](docs/NAP-TIEN-SEPAY.md)
- [Quy trình thực đơn và thanh toán](docs/QUAY-THUC-DON-VA-THANH-TOAN.md)
- [Kết quả kiểm chứng](docs/KIEM-CHUNG.md)
- [Kiểm chứng applet và giới hạn trên thẻ thật](docs/KIEM-CHUNG-APPLET-J3R180.md)

## Giới hạn kiểm chứng

Các luồng thẻ được mô phỏng bằng jCardSim trong môi trường phát triển. Việc build CAP hoặc chạy mô phỏng không tự xác nhận tương thích hoàn toàn với mọi thẻ vật lý, đầu đọc hay hành vi mất nguồn; cần kiểm tra riêng trên phần cứng mục tiêu. Xem [tài liệu kiểm chứng applet](docs/KIEM-CHUNG-APPLET-J3R180.md) để biết phạm vi đã xác nhận.
