# Kết quả tối ưu applet cho cấu hình thẻ J3R180

Ngày: 06/11/2026. Mốc full suite hoàn thành: 23:03 giờ Việt Nam.

**Đã triển khai và kiểm chứng phần mềm, CAP và giao thức trên mô phỏng. Chưa có thẻ thật để nghiệm thu J3R180.** Người dùng đã xác nhận trước mắt kiểm chứng bằng CAP và mô phỏng.

## Kết quả đã đạt

| Hạng mục | Kết quả |
|---|---|
| Payload các mảng RAM transient của applet chính | **1.249 byte**, giảm từ 58.753 byte, trong giới hạn thiết kế 1.536 byte |
| Hồ sơ bản rõ lớn nhất | 28.000 byte; đã thử round trip, đổi/reset PIN và restart |
| Ảnh theo interface ứng dụng | 24.576 byte; `ProfileCodec` và `CardClient.profile()` giữ nguyên format |
| Hai slot ciphertext trên thẻ mới | 2 × 28.016 = **56.032 byte**; snapshot cũ vẫn giữ nguyên dữ liệu/khóa khi nhập |
| Capability APDU / STATE / record scheme | Giữ 4 / TTS4 288 byte / scheme 3 |
| Snapshot simulator | Ghi TTM5; đọc TTM3, TTM4 và TTM5 |
| Converter và verifier | Oracle Classic **3.0.4**, thành công, mỗi bước 0 errors và 0 warnings |
| API import của CAP | Classic 3.0.4; là target API thấp hơn cấu hình JCOP 4/3.0.5 dự kiến, vẫn cần qualification trên thẻ |
| Package AID / version | F0 54 54 4D 01 / **2.0**; độc lập với capability APDU 4 |
| Student applet AID | F0 54 54 4D 01 01 |
| Probe applet AID | F0 54 54 4D 01 7E; instance riêng, khóa thử riêng |
| Full suite | **174 backend + 5 GUI = 179 ca**, 0 failures, 0 errors, 0 skipped |

Compiler Java 8 có ba cảnh báo rằng source/target 1.5 đã cũ; đây là cấu hình bytecode dùng cho converter có sẵn. Không đồng nhất những cảnh báo compiler này với kết quả converter/verifier.

Tổng RAM 1.249 byte chỉ bao gồm payload các mảng transient được applet cấp phát. Nó không bao gồm toàn bộ overhead/native crypto của thẻ. Kích thước CAP trên máy cũng không phải tổng footprint NVM sau INSTALL.

## Thay đổi kỹ thuật

- Thay `String`/`char` phục vụ curve và KDF bằng hằng byte Classic; giữ nguyên domain, nhãn và vector mật mã.
- Nhận prefix nhỏ và mã hóa hồ sơ theo block vào slot chưa active; giữ carry tối đa 15 byte qua chunk, hỗ trợ chunk bị tách rất nhỏ.
- Kiểm tra HMAC/padding trước khi trả bản rõ, giải mã phần được yêu cầu vào buffer nhỏ, hỗ trợ offset chưa căn AES block.
- Tái mã hóa đổi/reset PIN theo khối bằng hai ngữ cảnh CBC; tiếp tục giữ hai slot và đổi active tại COMMIT.
- Chuyển counter/flag của lần truyền sang transient, bỏ cấp phát domain theo mỗi lần ECC và giữ cache hash bản mã gắn với slot.
- Đường nạp/trừ/hoàn bù tiền không ghi hoặc xóa lại cả vùng ảnh. Giữ debit hold và tính idempotent của COMMIT/ACK/REVERSAL/RELEASE.
- Đọc snapshot cũ bằng đúng tập field của format đó; thêm cache cho bản mới. File không nhận dạng được giữ nguyên và báo lỗi thay vì bị xóa lúc khởi động.
- Sửa build để dùng converter có thật trong `tools.jar`, gọi `com.sun.javacard.converter.Main`, compile với bootclasspath Classic và chạy verifier rõ ràng.

Converter 3.0.4 gặp lỗi optimizer với biểu thức carry/borrow có nhiều toán tử điều kiện trong tính tiền. Đã khoanh vùng bằng debugger rồi viết lại phép tính bằng các bước `short` tương đương. Không sửa binary converter và không tắt verifier.

## Phạm vi kiểm thử

Full suite đã chạy một lượt với Maven offline từ `build/pom.xml`, JDK 25. PostgreSQL/HTTP dùng fixture có kiểm tra cứng URL `jdbc:postgresql://127.0.0.1:5548/ttm_test`; không dùng CSDL ứng dụng `ttm_cantin` và không phát sinh chuyển tiền ngân hàng thật.

Các nhóm mới gồm:

- `StreamingAppletTest`: 23 ca, gồm biên AES/chunk, hồ sơ 28.000 byte, ảnh 24 KiB, đổi/reset PIN, nhận từng byte, nhận thiếu/trùng, replay, offset ngẫu nhiên và ngắt nhận rồi restart.
- `LegacyCardCompatibilityTest`: 4 ca dùng snapshot thực sự do applet trước refactor tạo, gồm TTM3/TTM4 active, UPDATE đã PREPARE và DEBIT đã ACK còn hold.
- `ResourceProbeTest`: 1 ca kiểm tra instance chẩn đoán riêng và chạy tự kiểm tra mật mã qua reset trong jCardSim.

Các nhóm hiện có về vector KDF/AES/HMAC, ECC, protocol, coordinator, invoice, retention, SePay và HTTP đều đã chạy trong full suite. Kiểm thử này chứng minh hành vi trên đường simulator; chưa thay thế kiểm thử mất nguồn giữa một thao tác NVM thật.

Log cục bộ:

- `.local/j3r180-baseline.log`: mốc applet cũ và tạo fixture trước refactor.
- `.local/j3r180-streaming-tests.log`: hồi quy và snapshot cũ.
- `.local/j3r180-boundary-tests.log`: các ca biên và probe.
- `.local/j3r180-full-tests.log`: full suite 179 ca.
- `.local/j3r180-cap-debug.log`: converter/verifier cho CAP có debug symbols.

Không dùng log chứa dữ liệu fixture hoặc cấu hình riêng làm bản báo cáo chia sẻ nguyên trạng.

## Bản đóng gói ứng dụng

Đã chạy một lượt `package` với `-DskipTests` sau full suite, trên bản sao chỉ chứa source/pom/library của project trong staging. Cả backend Spring Boot, GUI, module card và shared đều đóng gói thành công. Việc bỏ test ở bước package dựa trên full suite vừa đạt, không được tính thành một lượt kiểm thử mới.

Các JAR và CAP nằm tại `.local/j3r180-release/artifacts`; gói tổng hợp là `.local/j3r180-release/j3r180-release.zip`. `release-manifest.json` ghi hash và kích thước từng artifact.

Backend đang chạy dùng JAR trong `backend/target`; quá trình đóng gói staging không ghi đè JAR đó, không restart backend và không chuyển dữ liệu ứng dụng đang dùng sang snapshot mới. Khi áp dụng bản mới vào môi trường đang dùng phải dừng phiên cũ, sao lưu phù hợp và thay bộ JAR nhất quán trước khi khởi động lại. Các phép thử trong phiên này dùng cấu hình/fixture riêng.

## Build và chạy trên JCIDE

Từ thư mục project:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File card/scripts/Build-CAP.ps1
```

Các đầu ra:

- `card/build/cap/vn/edu/smartcard/card/javacard/card.cap`.
- `card/build/cap/cap-manifest.json`: SHA-256, kích thước, target và trạng thái hardware.
- `card/build/jcide/TTM_Card.jcproj`: project JCIDE được tạo cùng source/class/CAP đã kiểm tra.

CAP mặc định có `Debug.cap` để dùng với JCIDE. Có thể dùng `-WithoutDebug` khi chuẩn bị artifact cài trên thẻ bằng công cụ GlobalPlatform; mỗi bản build có hash riêng trong manifest.

Mở project được tạo trong JCIDE, chọn simulator có các thuật toán cần dùng, bật PC/SC rồi Run. Dùng CAP đã build bằng script; thay đổi source phải được build lại từ thư mục source chính, không sửa bản source sinh ra trong `card/build/jcide`. Nếu JCIDE tự build bằng SDK cũ khác cấu hình script, kết quả đó cần được kiểm tra riêng.

Ứng dụng căn tin kết nối đầu đọc JAVACOS, chọn applet căn tin rồi dùng Admin để khởi tạo. Nhãn đầu đọc xuất hiện chưa có nghĩa thẻ ảo đã chạy hoặc đã hỗ trợ đủ mật mã. **Phiên này chưa có bằng chứng Run native JCIDE thành công**; các ca chức năng mô phỏng đã chạy bằng jCardSim.

## Đo tài nguyên qua PC/SC

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File card/scripts/Probe-Card.ps1 -ListReaders
powershell.exe -NoProfile -ExecutionPolicy Bypass -File card/scripts/Probe-Card.ps1 -Reader 'JAVACOS Virtual Contact Reader 0'
```

Lệnh thứ hai cần instance `ResourceProbeApplet` với AID F054544D017E đã được cài. Công cụ không tự cài/xóa applet. Cài và đo probe riêng trước khi cài instance chính; giữ đồng thời hai instance sẽ làm thay đổi ngân sách RAM cần đo.

Probe cấp arena 1.536 byte cùng bộ khóa/crypto dự kiến, có phép AES với vector cố định và phép HMAC/ECDSA sign/verify, đọc transient/persistent và commit capacity. Kết quả được xuất JSON, không xuất khóa. Giá trị `getAvailableMemory()` là thông tin tài nguyên ở thời điểm gọi, không phải phép đo đỉnh RAM toàn runtime. API version hex 0300 cũng không đủ xác định riêng bản vá 3.0.5 của thẻ.

Trên môi trường hiện tại lệnh liệt kê chưa trả về đầu đọc PC/SC khả dụng; chưa kết nối hay thay đổi thẻ/JCIDE nào.

## Phần còn phải kiểm chứng khi có phần cứng

1. Đúng J3R180/JCOP 4 SECID, quyền LOAD/INSTALL và khả năng đáp ứng đầy đủ HMAC/ECDSA/transient keys.
2. RAM cho native crypto, commit buffer thực và NVM tổng sau cài đặt, bao gồm overhead.
3. Thời gian APDU và SLO trên đầu đọc thật; T=1 trước, thêm T=0/contactless theo cấu hình sử dụng.
4. Ngắt nguồn thực trong lúc ghi slot và chuyển metadata; đối soát số dư và PIN sau kết nối lại.
5. Chạy chính bản CAP có hash được ghi trong biên bản nghiệm thu, với hồ sơ lớn nhất và chuỗi thao tác lặp.

Chỉ sau các bước này mới kết luận bản applet đã được nghiệm thu trên cấu hình J3R180 cụ thể. Các bước chưa làm tiếp tục để chưa kiểm chứng, không suy diễn từ 179 ca phần mềm đã đạt.
