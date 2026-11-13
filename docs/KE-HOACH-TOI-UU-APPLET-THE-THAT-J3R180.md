# Kế hoạch tối ưu applet để triển khai trên thẻ thật J3R180 JCOP 4

Ngày lập: 06/11/2026. Project: `C:/JavaCard/TTM_CuoiKy`.

Trạng thái cập nhật 06/11/2026: **Đã triển khai phần mềm và kiểm chứng CAP/mô phỏng/PostgreSQL/HTTP. Người dùng xác nhận chưa có thẻ thật; phần nghiệm thu J3R180 còn mở.** Xem [kết quả thực hiện](KIEM-CHUNG-APPLET-J3R180.md). Các ngân sách ghi là mục tiêu thiết kế bên dưới tiếp tục cần đối chiếu số đo phần cứng.

## 1. Mục tiêu và cách kết luận tính khả thi

Mục tiêu là đưa cùng nghiệp vụ căn tin hiện có lên một cấu hình thẻ thật được xác định rõ, lấy **NXP J3R180, JCOP 4 P71 SECID, Java Card 3.0.5 Classic** làm mẫu tham chiếu. Bản tối ưu tiếp tục chạy bằng jCardSim và có thể kết nối qua PC/SC, bao gồm đầu đọc ảo của JCIDE khi simulator tương thích.

Yêu cầu mới mở rộng mốc nghiệm thu trước đây của project: README hiện ghi nhận nghiệm thu bằng jCardSim và chưa chứng minh CAP/thẻ vật lý. Không dùng kết quả cũ để kết luận bản tối ưu đã chạy trên J3R180.

Từ chắc chắn trong kế hoạch được chuyển thành các điều kiện kiểm chứng cụ thể:

- Có giới hạn rõ cho bộ nhớ, dữ liệu đầu vào và các thuật toán bắt buộc.
- CAP được tạo và kiểm tra bằng toolchain phù hợp Java Card Classic.
- Đúng cấu hình thẻ vượt qua bài kiểm tra tài nguyên và mật mã.
- Toàn bộ nghiệp vụ bắt buộc, dữ liệu lớn nhất và phục hồi khi gián đoạn đều được thử trên thẻ mục tiêu.
- Báo cáo ghi đúng mức bằng chứng đang có. Khi chưa có thẻ, kết luận là thiết kế khả thi theo ngân sách và kết quả mô phỏng, chưa phải đã triển khai thành công trên thẻ thật.

Tên thương mại J3R180 hoặc nhãn 180K chưa đủ thay thế những kiểm tra này. Khả năng nạp applet, cấu hình SECID, thành phần cài sẵn và bộ nhớ thực tế còn trống phải được xác định cho tấm thẻ dùng nghiệm thu.

## 2. Phạm vi nghiệp vụ được giữ làm mốc

Đây là kế hoạch tối ưu cách xử lý bên trong applet và phần giao tiếp trực tiếp với applet. Các quyết định nghiệp vụ sau tiếp tục là mốc đối chiếu:

- Hồ sơ và ảnh vẫn được lưu trên thẻ ở dạng mã hóa. Giữ ảnh tối đa **24.576 byte, tức 24 KiB**; tổng bản rõ do `ProfileCodec` tạo tối đa **28.000 byte**.
- Thẻ sinh cặp khóa ECC P-256 và bí mật riêng; xác thực challenge bằng ECDSA-SHA256. Sinh viên dùng ECC rồi PIN; admin dùng tài khoản quản trị và ECC.
- Giữ KDF, AES-128-CBC, IV mới, HMAC-SHA256, định dạng PIN record và profile record scheme 3. PIN và khóa dẫn xuất sử dụng bộ nhớ tạm; NVM giữ các bản ghi mã hóa và khóa gốc theo thiết kế hiện có.
- Backend tiếp tục quản lý bộ đếm sai PIN, khóa ở lần sai thứ năm và quyết định cấp phiên. Giữ sự khác nhau giữa phiên quản lý và xác thực cho một hóa đơn.
- Giữ hai slot dữ liệu, PREPARE → COMMIT → ACK, operation ID/token, counter và version. Giữ DEBIT hold, REVERSAL và RELEASE; giao dịch hủy phải được đối soát/hoàn bù đúng một lần.
- Đầu đọc, máy quầy và APDU vẫn là đường tin cậy theo phạm vi đề tài. Tối ưu để vừa thẻ thật không tự tạo thêm cam kết bảo mật cho đầu đọc không tin cậy hoặc giao dịch ngoại tuyến.
- Giao diện người dùng, nghiệp vụ ngân hàng và schema SQL hiện tại là mốc tương thích. Kế hoạch này không đòi hỏi migration CSDL chỉ để giảm RAM của applet.

Nếu sau đo đạc cần thay một trong những điều kiện trên, phải ghi rõ tác động và điều chỉnh kế hoạch trước khi triển khai phần thay đổi đó.

## 3. Hiện trạng trước tối ưu đã đối chiếu từ mã nguồn

Các phát hiện trong bảng là **Project-Confirmed** tại thời điểm lập kế hoạch, trước khi refactor. Ngân sách và cách tối ưu ở các mục sau là **Proposed**; kết quả sau triển khai được ghi trong tài liệu kiểm chứng riêng.

| Vị trí | Hiện trạng | Tác động khi chạy trên thẻ thật |
|---|---|---|
| `StudentCardApplet.initRam()` | `stage` và `work` đều dài 28.688 byte; tổng payload các mảng transient là 58.753 byte | Vượt xa ngân sách bộ nhớ tạm của thẻ tham chiếu; cấp phát ngay lúc tạo applet |
| `curve()`, `hex()`, `kdf()` | Dùng `String`, `length()`, `charAt()` và tham số `char` | Cần loại phụ thuộc Java SE không có trong tập API Classic mục tiêu; `.class` chạy trên jCardSim chưa chứng minh chuyển CAP được |
| `prove()` | Tạo `byte[] domain` mới mỗi lần xác thực ECC | Trên thẻ có thể tạo thêm đối tượng persistent ở mỗi lần gọi; kế hoạch không dựa vào việc có GC để thu hồi |
| `expected`, `received`, `outputLength` | Đang là field `short` thông thường, được snapshot simulator loại khỏi dữ liệu lưu | Trên thẻ thật, field thông thường thuộc đối tượng persistent; cần chuyển giá trị phiên sang transient thay vì dựa vào serializer |
| `BEGIN`/`CHUNK`/`prepare()` | Nhận toàn bộ payload vào `stage`, sau đó mới xử lý | Cần thay bộ thu gom payload bằng bộ xử lý tuần tự và buffer cố định |
| `READ_PROFILE`/`decryptProfile()` | Mỗi lần đọc tối đa 180 byte lại xác minh/giải mã toàn bộ hồ sơ vào `work` | Hồ sơ lớn gây lặp công việc; RAM và thời gian tăng không phù hợp với cách đọc từng phần |
| `encryptProfile()` và đổi/reset PIN | `doFinal()` xử lý cả hồ sơ; đổi PIN giữ cả bản rõ để tái mã hóa | Cần chuyển sang xử lý từng khối, giữ nguyên quy tắc kiểm tra HMAC và padding |
| `ack()`/`clearInactive()` | Có đường dọn toàn bộ slot bản mã kể cả sau thao tác ví | Cần tránh ghi/xóa hàng chục KB Flash trong đường thanh toán chỉ thay metadata |
| `ReaderHub.SnapshotRuntime` | Allowlist quyết định field nào được lưu vào file mô phỏng | Sau refactor phải đối chiếu lại trạng thái persistent/transient và định dạng snapshot |
| `CardSynchronizer.run()` | Token thao tác là 32 byte ngẫu nhiên; hash của bản mã candidate được kiểm tra riêng | Giữ đúng ý nghĩa token khi viết bộ nhận streaming; không diễn giải token thành SHA/HMAC của payload |
| `Build-CAP.ps1` | Đường mặc định dùng SDK 3.0.4; thư mục đó hiện thiếu `lib/converter.jar` | Cần chuẩn bị toolchain CAP trước khi công nhận tương thích Classic |

API `java.lang` của Java Card Classic 3.0.5 không cung cấp `String` như Java SE. Việc dùng hằng byte thay chuỗi phải giữ nguyên chính xác byte của domain và nhãn KDF để không đổi kết quả mật mã. Tham khảo [Oracle: java.lang của Classic](https://docs.oracle.com/cd/E59935_01/api/java/lang/package-summary.html).

## 4. Cấu hình mục tiêu và bước kiểm tra thẻ

### 4.1. Mẫu tham chiếu

Tài liệu NXP JCOP 4 P71 mô tả J3R180 trong nhóm SECID 180K. Bảng bộ nhớ ví dụ nêu transient khả dụng 4.192 byte cho SECID basic/full; persistent khả dụng lần lượt 175.280 và 157.524 byte. Đây là số theo cấu hình của tài liệu, chưa phải phép đo trên thẻ của người dùng. [NXP Short Data Sheet, mục 2.2 và 2.7](https://p.globalsources.com/IMAGES/PDT/SPEC/358/K1198907358.pdf#page=8).

Ngưỡng thiết kế ban đầu:

| Điều kiện | Mục tiêu |
|---|---|
| Nền tảng | Java Card 3.0.5 Classic, cấu hình có đủ ECC/HMAC cần dùng |
| Giao tiếp nghiệm thu đầu tiên | Thẻ thật và đầu đọc PC/SC tiếp xúc; thử thêm không tiếp xúc nếu dùng trong buổi trình diễn |
| Ngân sách transient trước applet chính | Ít nhất 4.096 byte được xác định bằng phép đo có tính footprint của công cụ đo |
| NVM trống trước nạp applet chính | Ít nhất 96 KiB; mức này dành chỗ cho mục tiêu footprint và dự phòng bên dưới |
| Quyền cài đặt | Thẻ có thể LOAD/INSTALL applet bằng thông tin quản trị được phép sử dụng |
| Transaction buffer | Đo `getMaxCommitCapacity()` và thử đường cập nhật metadata dài nhất; phải đủ cả dữ liệu và overhead của runtime |

Các ngưỡng trên là điều kiện của cấu hình nghiệm thu đề xuất. Thẻ có bộ nhớ thấp hơn cần được đánh giá lại bằng số đo; không tự kết luận một biến thể có MIFARE/add-on sẽ đạt cùng ngân sách.

### 4.2. Applet kiểm tra tài nguyên trước khi tối ưu toàn bộ

Tạo một applet kiểm tra nhỏ, AID riêng và chỉ dùng dữ liệu thử. Công cụ này cần:

1. Ghi nhận model/cấu hình được xác nhận, phiên bản API, ATR/CPLC cần thiết và chế độ giao tiếp. Báo cáo chia sẻ chỉ dùng định danh cần thiết, có thể che phần định danh riêng của tấm thẻ.
2. Đọc persistent, transient RESET/DESELECT và commit capacity. Với persistent trên 32.767 byte, dùng overload trả kết quả 32 bit thay cho cách đọc `short` bị giới hạn.
3. Tạo số lượng khóa/đối tượng dự kiến của bản chính: ba khóa AES transient, bốn khóa HMAC transient, khóa HMAC gốc persistent, cặp khóa EC, ECDSA signer, HMAC signer, SHA-256, RNG và hai ngữ cảnh CBC cho tái mã hóa.
4. Cấp phát arena transient 1.536 byte; đo trước/sau khi tạo arena và đối tượng mật mã, sau SELECT và tại các điểm kiểm tra của thao tác.
5. Thực hiện vector P-256 key generation/sign/verify, AES-CBC, HMAC-SHA256, KDF, xử lý nhiều khối và key clearing sau reset/deselect. Việc `getInstance()` thành công chưa thay thế được phép tính có kết quả đúng.
6. Thử ghi metadata nguyên tử và mất nguồn/reset để xác định giới hạn transaction thực tế.

`getAvailableMemory()` cho biết một cận trên của bộ nhớ có thể còn dùng, chịu ảnh hưởng của overhead và phân mảnh. Những lần đọc này không trực tiếp đo được toàn bộ đỉnh RAM nội bộ của thuật toán; phải kết hợp với thử trường hợp xấu nhất và chạy lặp. Tham khảo [Oracle JCSystem: đo bộ nhớ](https://docs.oracle.com/cd/E59935_01/api/javacard/framework/JCSystem.html#getAvailableMemory(short[],%20short,%20byte)).

Nếu HMAC-SHA256, ECDSA-SHA256 hoặc loại khóa transient bắt buộc không hoạt động đúng trên cấu hình thử, cấu hình đó chưa đạt mục tiêu. Phương án ưu tiên là xác định đúng biến thể có đủ chức năng. Một phương án HMAC phần mềm, nếu thật sự cần, phải có đánh giá tài nguyên/vector riêng trước khi đưa vào kế hoạch triển khai.

## 5. Ngân sách bộ nhớ đề xuất

### 5.1. RAM

Dùng một arena transient cố định, hoặc số ít mảng có tổng kích thước tương đương. Các vùng có vòng đời chồng nhau được cấp riêng; chỉ tái sử dụng vùng sau khi dữ liệu cũ không còn cần và đã được xóa phù hợp.

| Vùng làm việc dự kiến | Payload tối đa |
|---|---:|
| Nhận APDU | 256 byte |
| Khối đầu ra/đầu vào mật mã | 256 byte |
| Scratch cho KDF, PIN record, header bản ghi | 256 byte |
| STATE/proof output dùng chung theo thời điểm | 288 byte |
| Header yêu cầu | 63 byte |
| Prefix thao tác nhỏ | 32 byte |
| Session và card ID tạm | 64 byte |
| Carry block, IV, tag và dữ liệu trung gian ngắn | 128 byte |
| Offset, độ dài, flags và trạng thái phiên | 64 byte |
| Dự phòng trong arena | 129 byte |
| **Tổng payload mảng transient** | **1.536 byte** |

Đây là bảng phân bổ thiết kế, không phải khẳng định mọi vùng phải tồn tại thành một mảng riêng. Trong implementation phải có sơ đồ offset/vòng đời để tránh ghi đè phần arena còn đang dùng.

Ngoài arena, dành ngân sách thiết kế tối đa 1.536 byte cho phần transient liên quan đến khóa/đối tượng/native crypto. Với baseline tối thiểu 4.096 byte, mục tiêu là còn ít nhất 1.024 byte dự phòng tại các điểm đo. **Khoản dành cho native crypto phải được xác nhận ở bước kiểm tra thẻ**, không suy ra chỉ từ độ dài khóa AES/HMAC.

Điều kiện đạt RAM gồm cả kiểm kê mọi transient allocation, các phép đo tại điểm kiểm tra và việc các đường xử lý xấu nhất chạy thành công. Không dùng một số `free RAM` sau khi chạy xong để tuyên bố đã đo đỉnh RAM.

### 5.2. Bộ nhớ persistent

- Tách giới hạn bản rõ 28.000 byte khỏi dung lượng bản mã. Với padding `80 00...`, ciphertext lớn nhất là **28.016 byte**.
- Hai slot mới cần **56.032 byte** cho payload ciphertext. Định dạng record và giới hạn hồ sơ vẫn giữ như hiện tại.
- Mục tiêu footprint tổng sau LOAD/INSTALL, gồm mã, đối tượng và dữ liệu applet, là **không quá 80 KiB**, được đo trên thẻ. Dung lượng file CAP trên máy không đại diện đầy đủ cho footprint này.
- Dành phần chênh đến điều kiện NVM đầu vào 96 KiB cho dự phòng, overhead và thay đổi nhỏ. Con số mục tiêu 80 KiB phải được xác nhận sau build và cài đặt.
- Sau INSTALL/SELECT đầu tiên, đường xử lý thông thường không tạo đối tượng mới. Hằng byte và các đối tượng crypto được tạo một lần; không dựa vào GC để tránh cạn persistent heap.

### 5.3. Transaction và độ bền ghi

Ghi ciphertext theo khối vào slot chưa active ngoài transaction lớn. Chỉ dùng transaction cho validity, slot active, phase, token, counter, version, balance và debit hold liên quan. Mục tiêu ban đầu là mỗi transaction sửa không quá 128 byte dữ liệu metadata; overhead của runtime phải được đo và đối chiếu commit capacity thực.

Đường CREDIT/DEBIT/REVERSAL/RELEASE không ghi lại hồ sơ/ảnh và không xóa cả slot ảnh. Dữ liệu slot cũ được đánh dấu không hợp lệ theo vòng đời, ghi đè có kiểm soát khi tái sử dụng. Nếu thực hiện dọn bản mã cũ, dùng thao tác có giới hạn và phục hồi được khi ngắt; không đưa lượt dọn hàng chục KB vào mỗi ACK của thanh toán.

## 6. Thiết kế tối ưu các luồng chính

### 6.1. Sửa để thực sự thuộc Java Card Classic

- Thay `hex(String)` và nhãn `String` trong KDF bằng hằng `byte[]` có byte xác định sẵn; loại các thao tác `String`/`char` cần thư viện Java SE.
- Giữ nguyên byte của curve P-256, domain `TTM3-AUTH` và các nhãn KDF hiện hành. Chạy vector độc lập để phát hiện thay đổi vô ý.
- Tạo domain/hằng một lần, bỏ cấp phát mảng trong `prove()` và mọi đường xử lý APDU thông thường.
- Chuyển `expected`, `received`, `outputLength`, offset đọc/ghi, flags và cache phiên vào vùng transient. Danh sách field persistent trên thẻ được rà trực tiếp theo mô hình Java Card.
- Dọn arena và khóa đúng các thời điểm logout, deselect, reset, lỗi và kết thúc ngữ cảnh. `select()` không khôi phục quyền sinh viên từ các field persistent cũ.
- Các module Java SE như JSON, Base64, `ProfileCodec`, JDBC và transport máy tính tiếp tục ở phía host. CAP chỉ chứa mã và API dành cho thẻ.

### 6.2. Nhận hồ sơ bằng streaming

Giữ đường gửi BEGIN → CHUNK → EXECUTE. Thay cách giữ mọi CHUNK trong `stage` bằng state machine sử dụng arena cố định:

```mermaid
flowchart LR
  A[BEGIN: kiểm tra header và thao tác] --> B[Nhận prefix: quyền và version]
  B --> C[CHUNK: gom đủ block AES]
  C --> D[Mã hóa và ghi slot chưa active]
  D --> C
  D --> E[EXECUTE: padding, HMAC, kiểm tra đủ dữ liệu]
  E --> F[Transaction nhỏ: đánh dấu PREPARED]
```

Quy tắc bắt buộc:

1. BEGIN và prefix phải xác định được quyền, operation ID/token, version, độ dài, loại thao tác và slot đích trước khi ghi candidate. Thao tác không hợp lệ bị từ chối trước lượt ghi Flash đầu tiên.
2. Nhận prefix cố định 17 byte và PIN mới nếu loại thao tác yêu cầu. PIN chỉ nằm trong transient trong thời gian cần thiết.
3. Slot đích được đánh dấu chưa hợp lệ bằng metadata thích hợp trước khi thay payload. Slot active tiếp tục là nguồn dữ liệu được công nhận.
4. Mỗi CHUNK có offset chính xác và nằm trong tổng độ dài khai báo. Chunk hiện tại tối đa 180 byte không nhất thiết chia hết cho block AES 16 byte; giữ phần dư tối đa 15 byte để ghép với lượt sau.
5. Mã hóa theo block, ghi ciphertext vào slot đích và tính HMAC đúng thứ tự dữ liệu hiện tại: loại record, header được MAC và ciphertext. Độ dài profile/IV/version phải được chốt để xây header nhất quán.
6. EXECUTE chỉ đánh dấu PREPARED khi nhận đủ dữ liệu, hoàn tất padding, kết thúc phép tính mật mã và có đầy đủ metadata candidate. Lỗi giữa chừng không công nhận bản ghi một phần.
7. Reset/deselect giữa CHUNK làm mất phiên nhận. Slot dở không được đọc như bản ghi hợp lệ; backend có thể hủy/retry theo trạng thái trước quyết định commit.
8. PREPARE lặp cùng operation ID/token đang pending không được ghi đè candidate đã chuẩn bị. Dùng nhánh nhận bỏ qua dữ liệu theo hợp đồng hiện tại hoặc trả kết quả lặp phù hợp; ID/token khác bị từ chối trước ghi. Trường hợp đã COMMIT/ACK giữ quy tắc replay hiện hành.
9. Trong một phiên nhận, lệnh khác dùng chung Cipher/HMAC/arena phải được điều phối rõ. STATUS/INFO không được phá ngữ cảnh nhận; lệnh không tương thích phải từ chối rõ, không âm thầm tái khởi tạo crypto.

Token 32 byte vẫn là token thao tác ngẫu nhiên của backend. Số lượng/offset chunk và độ dài kiểm soát tính đầy đủ của lần truyền; hash candidate vẫn được đối chiếu theo cơ chế đang có. Kế hoạch không gán ý nghĩa chữ ký hoặc MAC cho token này.

### 6.3. Đọc hồ sơ không giải mã toàn bộ trong mỗi APDU

Thực hiện hai giai đoạn:

1. **Xác minh trước khi trả bản rõ:** đọc ciphertext theo khối để tính/kiểm tra HMAC toàn record và kiểm tra padding block cuối. Chưa xuất dữ liệu hồ sơ nếu bước này thất bại.
2. **Trả dữ liệu từng phần:** giải mã khối chứa offset được yêu cầu vào buffer nhỏ, rồi trả phần cần thiết. Với CBC, xác định đúng IV hoặc block ciphertext ngay trước vùng bắt đầu; xử lý offset chưa căn block và phần cuối.

Một phiên đọc tuần tự được gắn với kết nối/ngữ cảnh, active slot và version của bản ghi. Nó có thể tái sử dụng kết quả kiểm tra MAC cho các phần tiếp theo của cùng lần đọc. Bất kỳ thay đổi record, đổi quyền, reset/deselect hoặc kết thúc phiên nào đều hủy trạng thái này.

Giữ cách gọi bằng offset của `CardClient.profile()`. Với offset không nối tiếp, thực hiện xác minh cần thiết và tính lại vị trí khối một cách an toàn; không trả dữ liệu dựa trên cache của một bản ghi khác. Mục tiêu là một lần quét xác minh và lượng giải mã tương ứng lượng dữ liệu đọc, thay cho giải mã cả hồ sơ trong từng phần 180 byte.

STATE tiếp tục dùng output 288 byte; EXPORT đọc trực tiếp từng đoạn ciphertext từ NVM. Hash toàn bản mã được tính khi hoàn tất candidate và lưu 32 byte cho mỗi slot, gắn với validity/version của record. Như vậy đọc hai phần STATE không phải băm lại cả ảnh nhiều lần. Hash cache không thay bước kiểm tra HMAC trước khi xuất bản rõ; `CardClient.cipher()` vẫn đối chiếu hash của toàn dữ liệu nhận được. Snapshot cũ chưa có cache được tính bổ sung từ ciphertext và header hợp lệ theo đúng định dạng hiện hành.

### 6.4. Đổi/reset PIN bằng tái mã hóa từng khối

- Xác minh toàn bộ HMAC và padding của bản ghi active trước khi tạo kết quả được công nhận.
- Dẫn xuất khóa mới theo PIN mới và cryptoVersion mới; giữ các khóa cũ/mới cần thiết trong transient.
- Đọc từng khối ciphertext cũ, giải mã vào arena rồi mã hóa ngay sang slot chưa active, với IV mới và HMAC mới.
- Dùng hai ngữ cảnh CBC đã được kiểm tra ngân sách ở bước probe để tránh làm mất trạng thái mã hóa/giải mã khi chuyển chiều. Các khối đưa vào native crypto được căn block.
- Chỉ PREPARE sau khi hoàn tất bản ghi mới; COMMIT chuyển active nguyên tử. Trước commit, dữ liệu cũ vẫn là dữ liệu có hiệu lực; sau quyết định commit, phục hồi theo operation ID hiện có.
- Đổi PIN thành công giữ phiên quản lý theo yêu cầu hiện tại và chuyển sang khóa mới; reset PIN admin giữ quy tắc backend về bộ đếm và trạng thái khóa.

API Cipher hỗ trợ xử lý nhiều phần, có quy tắc về block, vùng buffer chồng nhau và việc IV bị reset sau `doFinal()`. Implementation phải kiểm soát những điểm đó và có vector độc lập ở biên khối. [Oracle Cipher: update/doFinal](https://docs.oracle.com/cd/E59935_01/api/javacardx/crypto/Cipher.html).

Nếu hai ngữ cảnh CBC không đạt ngân sách probe, phải tối ưu lịch tái sử dụng đối tượng/IV và đo lại trước khi tiếp tục. Không công nhận đạt bằng cách bỏ MAC, lưu bản rõ ở NVM hoặc giảm chức năng mà chưa điều chỉnh phạm vi.

### 6.5. Nạp/trừ tiền và phục hồi

Payload tài chính hiện chỉ cần phần ngắn. Dùng buffer nhỏ riêng trong arena; không khởi động pipeline hồ sơ cho CREDIT, DEBIT hoặc REVERSAL.

Giữ nguyên các bất biến:

- Counter và version không quay lùi; COMMIT/ACK/RELEASE lặp không tạo tác động tiền lặp.
- ACK của DEBIT giữ hold qua reset cho đến SUCCESS hoặc hoàn bù theo quyết định backend.
- REVERSAL chỉ khớp debit ID, amount và wallet version của hold; không tạo CREDIT tùy ý để sửa số dư.
- Trước quyết định commit, hủy candidate và giữ active. Sau quyết định commit, xác định/hoàn tất đúng thao tác rồi xử lý kết quả hóa đơn.
- Thiếu thẻ thì giữ trạng thái chờ đối soát phù hợp; chỉ báo hoàn bù xong khi số dư trên thẻ và backend đã được xác nhận.

Metadata của một trạng thái PREPARED/COMMITTED phải tự nhất quán sau mất nguồn. Việc ghi phần metadata trước đó ngoài transaction chỉ được phép ở vùng chưa có hiệu lực và không được làm lộ candidate dở như bản ghi hợp lệ.

## 7. Tương thích và các file dự kiến thay đổi

### 7.1. Hợp đồng giữ làm mặc định

Giữ AID applet `F0 54 54 4D 01 01`, package AID `F0 54 54 4D 01`, INFO capability 4, STATE `TTS4` 288 byte, request header 63 byte, record header 77 byte và scheme 3. Giữ các scope/kind hiện tại, payload, giới hạn chunk và các kết quả tiền.

Đây là hướng thay implementation sau cùng interface APDU, để GUI và phần nghiệp vụ không phải học một giao thức mới. Chỉ giữ capability 4 khi bộ kiểm thử tương thích xác nhận các đặc tính quan sát được vẫn đáp ứng hợp đồng. Nếu quá trình đo cho thấy buộc phải thêm lệnh tiếp tục hoặc đổi định dạng, phải lập thay đổi capability và phối hợp `Protocol`/`CardClient` trước; không gắn byte capability mới cho một định dạng cũ một cách ngầm định.

### 7.2. Phạm vi file

| File/module | Thay đổi dự kiến |
|---|---|
| `card/.../StudentCardApplet.java` | Arena, hằng byte Classic, trạng thái transient, streaming record, rekey, transaction nhỏ và dọn slot có giới hạn |
| `card/scripts/Build-CAP.ps1`, `Compile-Applet.ps1` | Toolchain và export files khớp target; kiểm tra subset, converter, verifier; ghi version/hash artifact |
| Applet kiểm tra riêng dưới `card/` | Đo tài nguyên và kiểm tra thuật toán bằng AID riêng; không thêm đường xuất bí mật vào applet chính |
| `backend/.../CardClient.java` | Đối chiếu đọc từng phần, giới hạn, timeout và xử lý lỗi truyền; giữ interface cho nghiệp vụ khi wire không đổi |
| `shared/.../Protocol.java` | Tập trung các giới hạn cần chia sẻ và kiểm thử hợp đồng; đổi version chỉ khi thật sự thay wire |
| `shared/.../ProfileCodec.java`, `Profile.java` | Giữ format và giới hạn hiện tại; bổ sung kiểm thử biên dữ liệu nếu cần |
| `backend/.../ReaderHub.java` | Allowlist snapshot, khởi tạo runtime-only state sau restore, tương thích dữ liệu mô phỏng cũ và thử PC/SC |
| `backend/.../CardSynchronizer.java` | Chỉ sửa nếu cần thích ứng với nhận dở/replay; giữ quyết định commit và mô hình SQL hiện tại |
| Nhóm kiểm thử applet/protocol/crypto/tài chính | Bổ sung ca streaming, ngân sách, reset, replay và tương thích |
| `docs/THIET-KE.md`, `KIEM-CHUNG.md` | Cập nhật theo implementation và bằng chứng thật khi hoàn thành từng mức |

### 7.3. Snapshot và dữ liệu đang dùng

Thay đổi field hoặc kích thước slot phải có bước nhập snapshot cũ rõ ràng. Hướng dự kiến là đọc TTM3/TTM4 bằng serializer tương ứng và ghi phiên bản snapshot mới khi cần; phiên bản file snapshot độc lập với capability APDU và phiên bản Flyway.

Phải cập nhật bộ nhận dạng format và bộ lọc file lúc khởi động `ReaderHub` trước khi ghi snapshot phiên bản mới. Mã hiện tại có đường xóa file không được nhận dạng là V3/V4; đường này cần được điều chỉnh để file phiên bản mới hoặc chưa hiểu được giữ lại và báo lỗi rõ, không bị dọn nhầm. Kiểm thử khởi động lại với cả định dạng cũ, mới và không nhận dạng là điều kiện của bước chuyển snapshot.

Quá trình nhập phải giữ identity, private key, secret, hồ sơ, PIN record, số dư, counter/version và pending/debit hold. Dữ liệu ciphertext hợp lệ được sao chép theo độ dài thực; phần nhận dở không trở thành phiên đã xác thực. Kiểm tra hash bản mã và kết quả phục hồi trước/sau nhập.

Không xóa hoặc tạo lại thẻ mô phỏng đang dùng để vượt lỗi tương thích. Công việc triển khai bắt đầu trên fixture/thẻ thử riêng; dữ liệu ứng dụng đang có chỉ được chuyển sau khi đường tương thích đã đạt và có bản sao lưu phù hợp.

## 8. Kế hoạch triển khai theo thứ tự

| Chặng | Việc thực hiện | Đầu ra và điều kiện chuyển bước |
|---|---|---|
| **G0 — Chốt thẻ và toolchain** | Kiểm tra đúng cấu hình J3R180, quyền cài, đầu đọc; chuẩn bị converter/verifier; build và chạy applet probe | Bảng tài nguyên/crypto có số đo. Nếu chưa có thẻ, ghi rõ G0 phần hardware còn mở; có thể tiếp tục phần tương thích và mô phỏng trong phạm vi giả định |
| **G1 — Sửa tương thích Classic và nền bộ nhớ** | Loại String/char phụ thuộc Java SE, cấp phát theo APDU, field phiên persistent; tạo arena; sửa build | CAP qua kiểm tra subset/link/verify; giữ vector ECC/KDF; tổng mảng transient không vượt 1.536 byte |
| **G2 — Streaming ghi/đọc hồ sơ** | Thay stage/work lớn, xử lý prefix/block/padding/MAC, đọc theo offset | INIT/UPDATE/READ/EXPORT/STATE đạt với dữ liệu biên; bản ghi lỗi không trả bản rõ; bản ghi dở không có hiệu lực |
| **G3 — Rekey và giao dịch** | Đổi/reset PIN từng khối; transaction metadata; tránh ghi ảnh ở đường ví; giữ hold/reversal | Hai slot, replay, reset và mọi kết quả tiền giữ đúng; transaction dài nhất chạy được trên commit buffer mục tiêu |
| **G4 — Tích hợp và dữ liệu cũ** | `CardClient`, simulator snapshot, PC/SC, backend `ttm_test`, GUI | Cùng nghiệp vụ chạy được qua adapter mô phỏng và PC/SC; dữ liệu/snapshot cũ giữ identity và số dư; SQL/HTTP có bằng chứng thật |
| **G5 — Nghiệm thu phần cứng và báo cáo** | Cài đúng CAP lên J3R180, kiểm thử lớn nhất, chạy lặp và rút thẻ/mất nguồn | Bảng PASS/FAIL đầy đủ, số đo tài nguyên/thời gian, artifact xác định và biên bản thẻ thật |

Không gộp G0 vào cuối dự án: phép đo đầu tiên giúp xác nhận giả định RAM và sự hỗ trợ HMAC/ECDSA trước phần refactor dài. Nếu hardware chưa sẵn, kết quả G1–G4 vẫn có ích nhưng trạng thái nghiệm thu G5 tiếp tục để chưa kiểm chứng.

Toolchain phải có converter/export files tương ứng target 3.0.5 hoặc công cụ hỗ trợ target đó. Không dùng converter 2.2.2 làm bằng chứng cho applet 3.0.5 và không bỏ verifier để tạo CAP. Tham khảo [Oracle: target Java Card version](https://docs.oracle.com/en/java/javacard/3.1/guide/using-converter-target-java-card-version.html) và [Oracle: kiểm tra CAP/export](https://docs.oracle.com/cd/E59935_01/guide/verifying_cap_and_export_files.htm).

## 9. Ma trận kiểm chứng bắt buộc

| Nhóm | Ca cần kiểm tra | Điều kiện đạt |
|---|---|---|
| Tương thích Classic | Compile → convert → verify → LOAD → INSTALL → SELECT | Artifact đi qua từng bước với export files/target đã ghi nhận |
| Mật mã | P-256, ECDSA-SHA256, AES-CBC, HMAC-SHA256, KDF và IV | Vector khớp phép tính độc lập trên host; reset/deselect xóa khóa/quyền tạm |
| Biên block/chunk | Độ dài 1, 15, 16, 17, 175, 176, 179, 180, 181 byte ở dữ liệu/piece phù hợp; prefix tách qua chunk | Đọc lại đúng byte; padding và phần dư không sai; không vượt arena |
| Dữ liệu lớn nhất | Ảnh 24.576 byte, hồ sơ hợp lệ lớn nhất tới 28.000 byte; đầu vào vượt giới hạn | INIT/UPDATE/READ/rekey đúng; đầu vào quá giới hạn bị từ chối trước tác động có hiệu lực |
| Nhận lỗi | Thiếu/trùng/sai thứ tự chunk, sai offset, tổng độ dài sai, BEGIN khác chen vào | Từ chối rõ; active không đổi; phiên có thể được dọn/phục hồi |
| Replay | PREPARE/COMMIT/ACK/ABORT/RELEASE lặp; operation/token xung đột | Không ghi đè candidate đang pending và không lặp tác động nghiệp vụ |
| Chống sửa record | Sửa ciphertext, IV, tag, version, độ dài và padding | Không trả bản rõ từ bản ghi không hợp lệ; không tính lỗi MAC thành một PIN sai |
| Quyền và PIN | ECC trước PIN; PIN sai; admin reset; logout/reset; lifecycle bị khóa | Quyền và bộ đếm giữ đúng chính sách hiện tại |
| Hai slot | INIT/UPDATE/đổi PIN bị ngắt trước và sau COMMIT | Chỉ bản cũ hoặc bản mới hợp lệ; không có bản ghi trộn hai phiên bản |
| Tài chính | CREDIT/DEBIT/REVERSAL/RELEASE, thiếu tiền, hủy hóa đơn, lặp và backend restart | Số dư/counter/version/ledger khớp; hoàn bù đúng debit gốc và đúng một lần |
| Gián đoạn | Ngắt sau BEGIN, trong CHUNK, sau PREPARE, sau COMMIT, trước/sau ACK/RELEASE | Recovery xác định được kết quả; không cấp lại quyền PIN từ trạng thái persistent |
| Ghi NVM | Xác thực/đọc/số dư lặp 1.000 lần; cập nhật hồ sơ/rekey có kiểm soát | Không có xu hướng mất persistent heap theo request; wallet không ghi lại cả ảnh |
| Snapshot | Snapshot TTM3/TTM4, active và pending/hold, chuyển sang định dạng mới nếu có | Identity, PIN, hồ sơ, tiền và khả năng phục hồi được giữ; bí mật phiên không xuất hiện trong dữ liệu lưu |
| PC/SC thật | T=1 trước; T=0 và contactless nếu nằm trong cấu hình dùng thực tế | SELECT/APDU/độ dài/SW/lỗi mất thẻ được xử lý đúng trên đường thật |
| SQL/HTTP | Các nhóm đồng bộ, coordinator, PIN, topup và invoice liên quan chạy trên PostgreSQL `ttm_test` | Assertion thật chạy qua; kết quả simulator không thay thế được bằng chứng SQL |

Tận dụng `AppletFlowTest`, `ProtocolFlowTest`, `CardCryptoTest`, `ReversalAppletTest`, `SynchronizationFlowTest`, `InvoiceOutcomeTest`, `HttpFlowTest` và các fixture hiện có. Vector mật mã tiếp tục được tính độc lập; kiểm thử nghiệp vụ đi qua interface APDU/host thay vì chỉ kiểm tra các giá trị nội bộ của implementation mới.

Chạy nhóm kiểm thử sát phần đang đổi. Sau khi các nhóm liên quan đạt, chạy một lượt kiểm tra rộng cần thiết cho bản tích hợp; chỉ chạy lại nhóm thất bại sau khi đã xác định và sửa nguyên nhân. Sau triển khai, full suite đã đạt 179 ca; xem báo cáo kiểm chứng để phân biệt phần mềm và phần cứng.

## 10. Kiểm thử phần cứng và hiệu năng

### 10.1. Các số đo phải có

- NVM trước LOAD, sau LOAD, sau INSTALL, sau tạo hồ sơ lớn nhất và sau chuỗi thao tác lặp.
- Transient trước/sau arena, sau tạo các khóa/đối tượng crypto và tại các điểm kiểm tra trong INIT/READ/rekey/thanh toán.
- Commit capacity, transaction dài nhất, số byte/field sửa và khả năng phục hồi sau ngắt.
- Thời gian từng APDU, tổng một thao tác ứng dụng, số round trip và các lỗi transport/timeout.

Ghi rõ cả reset/deselect và những lần thực sự ngắt nguồn. `sim.reset()`, lỗi giả lập sau một APDU hoặc `disconnect()` không tự chứng minh đã kiểm tra mất nguồn vật lý ở giữa một lượt ghi.

Trên thẻ thử riêng, thực hiện ngắt ở các ranh giới giao thức và nhiều lần ngắt nguồn trong lúc ghi slot/đổi metadata bằng thiết bị/cách thao tác cho phép làm việc đó. Phạm vi vị trí cắt đã thử phải được ghi lại; không mô tả một số lượt thử là chứng minh cho mọi dạng lỗi điện phần cứng.

### 10.2. Mục tiêu thời gian để đo và chốt

Mục tiêu đề xuất cho cấu hình tiếp xúc: xác thực và thanh toán thông thường trong 5 giây ở phân vị 95; thao tác hồ sơ lớn nhất trong 30 giây ở phân vị 95. Ghi thêm lần chậm nhất và mọi timeout, không chỉ số trung bình. Đây là mục tiêu cần benchmark, chưa phải hiệu năng đã có.

Mọi APDU phải hoàn thành trong giới hạn thực tế của driver/đầu đọc; toàn bộ HTTP đồng bộ phải phù hợp timeout 45 giây đang có ở `ApiClient`. Kiểm tra riêng tạo khóa lúc INSTALL, thao tác rekey dài và sự ảnh hưởng của reader lock đến polling, heartbeat và phiên thanh toán.

Nếu một phép xử lý cần cơ chế tiếp tục qua nhiều lệnh hoặc thay timeout, đó là quyết định tích hợp phải được mô tả và kiểm thử rõ. Không dùng việc nâng timeout đơn thuần để thay thế xử lý RAM không giới hạn.

## 11. Bằng chứng để trình bày với giáo viên

Phân biệt bốn mức trong báo cáo:

| Mức | Bằng chứng | Có thể kết luận |
|---|---|---|
| Thiết kế | Bảng bộ nhớ, giới hạn dữ liệu, nguồn thông số và phân tích luồng | Thiết kế đáp ứng ngân sách mục tiêu theo các giả định đã ghi |
| CAP | Converter/verifier và import/export tương ứng | Applet đã được kiểm tra tính tương thích ở mức CAP |
| Simulator | jCardSim/JCIDE, vector, replay và các lỗi mô phỏng | Hành vi phần mềm đạt trong môi trường đã thử |
| Thẻ thật | LOAD/INSTALL, số đo và ma trận nghiệp vụ trên đúng J3R180 | Bản applet xác định đã hoạt động trên cấu hình thẻ thật đã ghi nhận |

Bộ hồ sơ bàn giao dự kiến:

1. File CAP, SHA-256, version source/package, toolchain và danh sách API import.
2. Cấu hình thẻ/đầu đọc, phiên bản phần mềm host và bảng bộ nhớ trước/sau.
3. Ma trận PASS/FAIL, dữ liệu biên, phép đo thời gian và kết quả gián đoạn/phục hồi.
4. Demo ứng dụng kết nối đầu đọc thật: khởi tạo → ECC/PIN → đọc ảnh/hồ sơ → đổi PIN → nạp/trừ tiền qua luồng được phép → rút/cắm lại và đối soát.
5. Báo cáo phân biệt rõ mức đã đạt, giới hạn còn mở và cấu hình hỗ trợ. Không khẳng định mọi thẻ mang tên J3R180 đều tương đương mẫu đã thử.

Log chia sẻ chỉ giữ INS, độ dài, SW, thời gian, mã ca và hash cần thiết. Payload UNLOCK/PIN, khóa, token truy cập và dữ liệu cá nhân thật được loại khỏi log nộp bài. Applet chẩn đoán dùng AID riêng; production không có APDU xuất bí mật để phục vụ kiểm thử.

## 12. Tiêu chí hoàn thành kế hoạch triển khai

- [ ] G0 có số đo trên đúng mẫu thẻ hoặc đánh dấu rõ phần hardware chưa thực hiện.
- [ ] CAP qua converter/verifier phù hợp; không còn phụ thuộc String/char Java SE trong mã thẻ.
- [ ] Tổng payload mảng transient không quá 1.536 byte; native crypto và bộ nhớ dự phòng được xác nhận bằng probe và bài chạy xấu nhất.
- [ ] Footprint persistent đạt ngân sách đo trên thẻ; không cấp phát đối tượng mới theo từng APDU.
- [ ] Ảnh 24 KiB/hồ sơ 28.000 byte vẫn đọc, ghi, đổi/reset PIN và phục hồi được.
- [ ] Record MAC/padding được kiểm tra trước khi xuất bản rõ; PIN/khóa dẫn xuất tiếp tục ở transient.
- [ ] PREPARE/COMMIT/ACK và DEBIT hold/REVERSAL/RELEASE đạt các ca lặp, reset, hủy và mất phản hồi.
- [ ] Snapshot cũ và dữ liệu đang dùng có đường tương thích đã thử.
- [ ] Các assertion SQL/HTTP liên quan đã chạy trên PostgreSQL thử, không chỉ trên simulator.
- [ ] G5 có bằng chứng cài và chạy trên J3R180; nếu chưa có thì báo cáo chưa nghiệm thu thẻ thật.

## 13. Các nguồn mã cần dùng khi triển khai

- [Applet hiện tại](../card/src/main/java/vn/edu/smartcard/card/StudentCardApplet.java).
- [Giao tiếp host–thẻ](../backend/src/main/java/vn/edu/smartcard/backend/CardClient.java).
- [Giao thức và giới hạn](../shared/src/main/java/vn/edu/smartcard/shared/Protocol.java).
- [Profile codec](../shared/src/main/java/vn/edu/smartcard/shared/ProfileCodec.java) và [giới hạn ảnh](../shared/src/main/java/vn/edu/smartcard/shared/Profile.java).
- [Đồng bộ thao tác](../backend/src/main/java/vn/edu/smartcard/backend/CardSynchronizer.java).
- [Đầu đọc và snapshot](../backend/src/main/java/vn/edu/smartcard/backend/ReaderHub.java).
- [Build CAP](../card/scripts/Build-CAP.ps1).
- [Thiết kế hiện hành](THIET-KE.md) và [bằng chứng đã ghi nhận](KIEM-CHUNG.md).

Tài liệu này đề xuất hướng triển khai cho yêu cầu thẻ thật mới. Các tài liệu thiết kế và kiểm chứng hiện hành chỉ được cập nhật kết quả tương ứng sau khi công việc đó thực sự được thực hiện.
