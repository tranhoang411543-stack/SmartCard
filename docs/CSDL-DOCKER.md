# PostgreSQL trong Docker Desktop

Ngày chuyển: 06/11/2026. Chuyển bằng pg_dump/pg_restore từ cụm PostgreSQL 18.0 Windows của project sang PostgreSQL 18.0 Linux, không đổi major/minor hoặc mật khẩu.

## Dùng hằng ngày

- Mở Docker Desktop, vào Containers, tìm nhóm `ttm-cuoiky` và container `ttm-cuoiky-postgres`, bấm Start.
- Hoặc nhấp đúp `06-CSDL-Docker.cmd` trong thư mục project. Lệnh tự mở Docker Desktop khi cần và đợi CSDL healthy.
- `01-Chuan-bi.ps1`, `02-Backend.ps1` và bước chuẩn bị của `00-Build.cmd` đã nhận biết CSDL Docker từ `.local/database.runtime.json`.
- Dừng riêng CSDL bằng nút Stop trong Docker Desktop hoặc `scripts/DockerDatabase.ps1 -Action Stop`. Dữ liệu nằm trong volume và vẫn còn sau Stop/Start/tạo lại container.

Docker có restart policy `unless-stopped`: container đang chạy sẽ được khởi động lại cùng engine; nếu đã chủ động Stop thì bấm Start hoặc chạy file `.cmd`.

## DBeaver và backend

| Trường | Giá trị |
|---|---|
| Host | 127.0.0.1 |
| Port | 5548 |
| Database ứng dụng | ttm_cantin |
| Database kiểm thử | ttm_test |
| Username | ttm |
| Password | Giá trị `db.password` hiện có trong `.local/server.properties`, không đổi |

JDBC ứng dụng: `jdbc:postgresql://127.0.0.1:5548/ttm_cantin`. Chỉ publish cổng trên loopback của máy.

Backend vẫn chạy Java trên Windows và đọc cấu hình cũ; chỉ PostgreSQL chuyển vào Docker. Các file thẻ/khóa/cấu hình backend không chuyển sang image.

## Tài nguyên và cấu hình

- Compose project: `ttm-cuoiky`.
- Container: `ttm-cuoiky-postgres`.
- Volume: `ttm_cuoiky_postgres18_data`, mount `/var/lib/postgresql`; PGDATA thực tế `/var/lib/postgresql/18/docker`.
- Image local: `ttm-cuoiky-postgres:18.0`, build từ image chính thức postgres:18.0 đã pin digest trong `docker/postgres/Dockerfile`.
- Secret file: `.local/docker/postgres-password.txt`; Compose dùng POSTGRES_PASSWORD_FILE, không ghi mật khẩu vào compose.yaml.
- Cờ chuyển đổi: `.local/database.runtime.json`. Launcher từ chối tạo DB rỗng nếu volume đã chuyển bị mất.

Image bổ sung liên kết `Asia/Saigon` tới `Asia/Ho_Chi_Minh`: Java trên Windows gửi tên cũ này ở startup JDBC, còn base image không có alias đó. Sửa tại image để giữ cấu hình và cách hiển thị giờ của các client. Không đổi múi giờ JVM hoặc giá trị dữ liệu trong CSDL.

Nguồn: [PostgreSQL official image: PGDATA và secrets](https://hub.docker.com/_/postgres), [IANA backward timezone links](https://github.com/eggert/tz/blob/main/backward).

## Bản sao và bằng chứng chuyển dữ liệu

Bản sao lưu trong `.local/backups/docker-migration-20261106-084519-f45ab1/`:

- ttm_cantin.dump, ttm_test.dump: dump custom format PostgreSQL.
- source-manifests.json, docker-manifests.json: số dòng/hash nội dung từng bảng, giá trị sequence, số lượng đối tượng schema và large objects.
- dump-sha256.json: checksum file backup.
- server.properties, client.properties và thư mục cards: bản sao cấu hình/thẻ tại thời điểm chuyển.
- completed.json: trạng thái hoàn tất.

Kết quả sau khôi phục, sau tạo lại container ở cổng chính, sau Stop/Start và sau sửa image timezone:

| Database | Bảng | Tổng dòng | Sequence | Đối chiếu |
|---|---:|---:|---:|---|
| ttm_cantin | 17 | 11 | 8 | Khớp bản nguồn |
| ttm_test | 17 | 312 | 8 | Khớp bản nguồn |

Đã kiểm tra kết nối JDBC bằng JDK25/driver của backend và chính file server.properties: đạt. Đã chạy launcher `.cmd` bằng Windows PowerShell và cả nhánh chuẩn bị CSDL kiểm thử: đạt. Chi tiết xác nhận trong `.local/docker/migration-verification.json`.

PostgreSQL cũ đã dừng, thư mục `.local/postgres` vẫn giữ nguyên tại thời điểm chuyển. Đây là bản gốc trước khi dùng Docker; nếu sau này đã ghi dữ liệu mới vào Docker thì cần backup dữ liệu mới trước khi phục hồi về nguồn cũ.

Việc chuyển CSDL giữ nguyên schema/migration hiện có (V1/V2 ở nguồn ứng dụng); không chạy Flyway hoặc full suite trong lần chuyển này. Backend áp dụng migration của ứng dụng khi được khởi động như bình thường. Không dùng kết quả kết nối JDBC để tuyên bố các luồng thanh toán đã nghiệm thu.

## Công cụ chuyển một lần

`06-Chuyen-CSDL-sang-Docker.ps1` gọi script di chuyển có kiểm tra phạm vi, quiesce backend đã xác nhận, backup, restore ở 5549, đối chiếu nguồn/đích rồi mới ngắt PostgreSQL cũ và chuyển Docker sang 5548. Không xóa cụm nguồn, không xóa volume và không ghi đè một DB đích không khớp. Nếu lỗi ở bước chuyển cổng, script dừng riêng container project và thử bật lại cụm nguồn.

Khi đã có cờ chuyển đổi thành công, chạy lại script chỉ báo đã chuyển, không sao chép lại dữ liệu cũ đè lên Docker.
