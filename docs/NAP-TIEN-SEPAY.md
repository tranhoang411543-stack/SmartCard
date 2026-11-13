# Nạp tiền BIDV VA qua SePay và ngrok

Luồng hiện hành: quầy tạo yêu cầu nạp → backend lấy ảnh QR từ VietQR.app và trả yêu cầu cho quầy → quầy mở kết nối nhận trạng thái SSE → khách chuyển tiền vào VA → SePay Live gửi JSON → ngrok Docker chuyển POST /webhook vào backend → lưu BANK_RECEIVED trong PostgreSQL → backend nhận tín hiệu sau commit và ghi tiền lên đúng thẻ đang có phiên sinh viên quản lý hợp lệ → báo trạng thái CREDITED cho quầy.

## Tài khoản và nội dung QR

- Ngân hàng: BIDV; VA nhận: `96247Y6CZZ`; chủ tài khoản hiển thị: `TRAN HOANG THU`.
- Ảnh: `https://vietqr.app/img`, tham số `bank`, `acc`, `template=qronly`, `amount`, `des`, `showinfo=true`, `holder`. Cả nạp tiền và thanh toán hóa đơn đều dùng mẫu `qronly`; nút tải lại trên hóa đơn sẽ lấy ảnh mới và giữ nguyên mã tham chiếu, số tiền và hạn thanh toán.
- Nội dung nạp tiền hiện hành V9: `NT` + toàn bộ MSSV + 8 số phiếu nạp, ví dụ `NTCT07035200000842`. Hóa đơn dùng `TT` + MSSV + 8 số hóa đơn, ví dụ `TTAT01020300000125`.
- `topups.reference` vẫn là ID nội bộ duy nhất. `Api.Topup.reference` hiển thị mã 18 ký tự từ `sepay_topup_requests.bank_memo`. V5/V8 giữ làm lịch sử; V9 thêm unique index cho mã từng yêu cầu.
- Giữ AT/CT/DT để phân biệt MSSV; 8 số cuối phân biệt từng phiếu. Tra đúng toàn bộ mã, không fallback theo sinh viên/cửa sổ mới nhất. Không cần chờ giây kế tiếp giữa hai yêu cầu. Mẫu cũ, hết hạn/hủy giữ REVIEW, HTTP 200 nhưng không ghi tiền.

## Khởi động hằng ngày

Chạy `02-Backend.ps1`. Script chuẩn bị CSDL, cấu hình SePay, cập nhật backend khi JAR/cấu hình đổi, đợi health rồi khởi động container `ttm-cuoiky-ngrok`.

Container dùng image ngrok chính thức pin digest, restart policy `unless-stopped`, upstream `http://host.docker.internal:18080`. Docker secret `.local/docker/ngrok.yml` được tạo từ cấu hình ngrok đã đăng nhập trên máy, gồm trường hợp Microsoft Store chuyển AppData vào LocalCache. Không đưa authtoken vào command line hoặc environment của container; không in token. Inspector chỉ bind `127.0.0.1:4041`; request inspection tắt để không lưu header API Key.

URL giữ nguyên: `https://hardening-surname-entity.ngrok-free.dev/webhook`. Ngrok luôn đổi Host upstream thành `ttm-sepay-webhook.internal`; filter backend chỉ cho POST chính xác `/webhook` đi qua Host này. Đường admin, sinh viên, health và dashboard test không được công bố qua tunnel. Backend chính vẫn bind loopback. Không thêm Traffic Policy, tài khoản trả phí hoặc chuyển tiếp webhook ngân hàng qua receiver không xác thực của WebhookTest.

Lệnh quản lý riêng:

```powershell
.\07-Webhook-SePay.ps1 -Action Configure
.\07-Webhook-SePay.ps1 -Action Status
.\07-Webhook-SePay.ps1 -Action Stop
.\07-Webhook-SePay.ps1 -Action Start
```

Nếu ngrok báo domain đang dùng, dừng riêng tunnel WebhookTest đã xác định trước khi khởi động container. Không dừng ngrok hoặc container không thuộc project. Nếu Docker Desktop hoặc backend tắt, endpoint chưa thể nhận callback; bật tùy chọn retry ở SePay và dùng nhật ký gửi lại khi cần.

## Cấu hình một lần trên SePay Dashboard

Giữ URL webhook trên. Chọn:

1. Môi trường **Live**, sự kiện **Có tiền vào**, JSON.
2. Tài khoản BIDV đã liên kết và VA **96247Y6CZZ**; tài khoản/VA phải hoạt động.
3. Tạo sáu mẫu tiền tố **TTAT, TTCT, TTDT, NTAT, NTCT, NTDT**, mỗi mẫu hậu tố chỉ số, tối thiểu/tối đa đều **14** (6 số MSSV + 8 số yêu cầu). Trên webhook của đúng tài khoản/VA, để trống bộ lọc tiền tố và tắt **Bỏ qua giao dịch không có mã** để nhận chứng từ cần đối soát. Tắt mẫu TTM/CTHD cũ và mẫu ngắn trùng tiền tố.
4. Bảo mật **API Key**. Dán nguyên nội dung `.local/sepay-webhook-api-key.txt` vào ô khóa, không dán thêm chữ `Apikey`. SePay tự gửi header `Authorization: Apikey <key>`.
5. Bật gửi lại khi server trả HTTP ngoài 200–299 để phục hồi sau mất kết nối/backend tạm dừng.

Có thể chép khóa vào clipboard bằng PowerShell trên máy, không in khóa hoặc gửi qua chat:

```powershell
Get-Content -Raw 'C:\JavaCard\TTM_CuoiKy\.local\sepay-webhook-api-key.txt' | Set-Clipboard
```

Backend trả 401 khi thiếu/sai khóa; không hỗ trợ webhook nạp tiền không xác thực. Cấu hình nằm trong `.local/server.properties` hoặc biến `TTM_`: `bank.provider=sepay`, `bank.code`, `bank.account`, `bank.name`, `sepay.webhook.api.key`. `bank.main.account` tùy chọn để kiểm tra thêm tài khoản chính khi payload có `subAccount` là VA. Casso vẫn có nhánh tương thích riêng khi `bank.provider=casso`; cấu hình chạy hiện hành tắt nhánh Casso.

## Đối chiếu và ghi tiền

Webhook kiểm tra API Key, ngân hàng, VA trong `subAccount` (hoặc `accountNumber` khi VA ở đó), loại `in/out`, số tiền VND nguyên dương, ID và thời gian Việt Nam. `code` và `content` phải thống nhất; nhiều mã hoặc mã không rõ thì giữ REVIEW, không tự cộng.

SePay ID và referenceCode được thêm namespace `SEPAY:` rồi lưu bằng unique constraints hiện có. Nhận lại cùng ID hoặc cùng mã ngân hàng không tạo tác động mới. Backend lưu kết quả đối chiếu trong giao dịch SQL; bộ xử lý nền thực hiện thao tác ghi thẻ. HTTP 200 `{"success":true}` chỉ xác nhận đã nhận callback, không có nghĩa thẻ đã được ghi. Lỗi xác thực/JSON/SQL trả lỗi để không báo nhận thành công giả.

Mỗi yêu cầu mới có mã `NT` riêng gắn với số phiếu nạp. Chỉ yêu cầu còn ở trạng thái chờ, đúng số tiền và còn hiệu lực khi callback được xử lý mới thành `BANK_RECEIVED`; thời điểm giao dịch ngân hàng cũng phải nằm trong cửa sổ hợp lệ của yêu cầu. Chuyển khoản không khớp được giữ để đối soát.

Backend không còn quét khoản nạp mỗi 2,5 giây. PostgreSQL gửi tín hiệu sau khi thay đổi trạng thái được commit; backend xử lý ngay khoản `BANK_RECEIVED` cho đúng thẻ còn kết nối và có phiên sinh viên quản lý. Quy trình vẫn dùng PREPARE/COMMIT/ACK và chỉ chốt `CREDITED` khi hai phía đồng bộ. Nếu không thể hoàn tất, hệ thống giữ trạng thái đã lưu, hiển thị hướng xử lý và xử lý lại khi phiên thẻ được khôi phục; không tự giả định đã ghi tiền thành công. Các lần nạp trong cùng phiên quản lý không hỏi lại PIN. Không ghi số dư trực tiếp để lách giao thức.

Màn hình quầy nhận thay đổi qua SSE và tự kết nối lại nếu đường truyền gián đoạn; sau khi kết nối lại, backend gửi trạng thái hiện tại từ cơ sở dữ liệu. Nếu kết nối PostgreSQL dùng để nhận tín hiệu bị gián đoạn, backend mở lại kết nối, kiểm tra các yêu cầu còn chờ và tiếp tục xử lý. Yêu cầu chưa nhận tiền được hẹn hết hạn một lần; đồng hồ trên màn hình chỉ cập nhật thời gian hiển thị, không hỏi trạng thái liên tục.

Callback của thẻ đã xóa được nối về chứng từ `refund_items` qua mapping được giữ; không tạo lại đăng ký hoặc tự chuyển khoản hoàn ngân hàng. Tiền sai số tiền, hết hạn, không có yêu cầu hoặc không rõ mã giữ để đối soát.

## Kiểm tra kết nối và luồng thật

`Verify-SePayWebhook.ps1` gửi probe `id=0` bằng API Key qua URL công khai: cần HTTP 200 success=true, thiếu khóa phải 401, API ứng dụng qua tunnel phải 404. Probe không tạo giao dịch hoặc cộng tiền; script đối chiếu số dòng ứng dụng trước/sau và lưu `.local/verification-sepay-public.json`. Đây là bằng chứng kết nối, không phải xác nhận tiền thật.

Để thử nghiệp vụ: đăng nhập sinh viên, tạo QR một khoản nạp, kiểm tra đúng VA/số tiền/nội dung rồi chuyển khoản bằng ứng dụng ngân hàng. Kiểm tra SePay Live có giao dịch và Delivery logs báo thành công. Giữ thẻ kết nối để số dư tự cập nhật; nếu đã rút, kết nối đúng thẻ và nhập PIN lại. Không sử dụng Test Mode để ghi tiền vào ví ứng dụng thật.

Các test `SePayGatewayTest`, `SePayFlowTest`, `SePayHttpFlowTest` dùng dữ liệu riêng của ttm_test và fixture; không xác nhận ngân hàng giả cho ttm_cantin. `SnapshotPersistenceTest` kiểm tra khóa file tạm thời trên Windows: chỉ thử lại atomic replace một lần sau 100 ms; lỗi kéo dài vẫn trả lỗi và giữ snapshot cũ.

## Khi SePay nhận 401 SEPAY_AUTH

Nếu probe có khóa của project trả 200 nhưng callback SePay trả 401, kiểm tra chính webhook đang hoạt động trên Dashboard. Danh sách phải hiển thị **API Key**. Nếu vẫn là **Không xác thực**, vào Sửa → Bảo mật → API Key, nhập khóa từ `.local/sepay-webhook-api-key.txt` rồi bấm **Cập nhật**. Chỉ dán nội dung khóa, không thêm tiền tố `Apikey`. Khóa webhook được tạo sẵn trong project; ngrok dùng authtoken riêng để mở tunnel.

Sau khi sửa, phát lại chính giao dịch thất bại trong Lịch sử gửi. Dòng 401 cũ được giữ làm lịch sử; cần xem lần phát lại mới và trạng thái khoản nạp. Không yêu cầu khách chuyển lại tiền khi khoản chuyển gốc đã có trên SePay. HTTP 200 xác nhận backend nhận callback; CREDITED cùng card operation SYNCED xác nhận đã ghi lên thẻ. Nếu BANK_RECEIVED, kết nối đúng thẻ và xác thực sinh viên lại.

Ngày 06/11/2026 đã kiểm chứng trường hợp này bằng khoản chuyển thật 10.000đ, mã TTM070352: phát lại HTTP 200 lúc 18:37:40; đúng một CREDIT APPLIED, topup CREDITED, thao tác thẻ SYNCED, số dư 0 → 10000. Xem [bằng chứng kiểm chứng](KIEM-CHUNG.md).

## Tài liệu gốc

- [SePay: payload, success=true, retry, chống trùng](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook).
- [SePay: API Key và các kiểu xác thực](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc).
- [SePay: BIDV cá nhân nhận tiền qua VA](https://docs.sepay.vn/ket-noi-bidv.html).
- [ngrok: Docker và host.docker.internal](https://ngrok.com/download/docker).
- [ngrok: cấu hình agent v3](https://ngrok.com/docs/gateway/agent/config/v3).
- [VietQR.app](https://vietqr.app/), cùng hợp đồng tham số do người dùng cung cấp; endpoint ảnh đã được kiểm tra trực tiếp.


## Mã chuyển khoản TT/NT theo MSSV (07/11/2026)

Luồng mua món dùng cùng tài khoản/VA và POST /webhook: **TT + MSSV + 8 số hóa đơn**, còn nạp thẻ dùng **NT + MSSV + 8 số phiếu nạp**. Tổng độ dài 18 ký tự: TT/NT, MSSV đầy đủ và số yêu cầu duy nhất. Xem [cấu hình và quy tắc V9](MA-CHUYEN-KHOAN-TT-NT.md). Mã cũ trong các biên bản chuyển khoản ngày 06/11 bên trên là bằng chứng lịch sử, không phải cấu hình phát hành QR mới.

Webhook đã xác thực trả HTTP 200/success=true cả khi yêu cầu hết hạn/đã hủy, nhưng chỉ lưu chứng từ REVIEW. Chỉ giao dịch đúng mã/tài khoản/số tiền, đúng cửa sổ ngân hàng và yêu cầu vẫn còn hiệu lực tại lúc xử lý mới ghi nhận thanh toán/nạp. Các khoản không khớp không tự được ghép vào yêu cầu mới; callback cùng ID chỉ có một tác động.

Chi tiết cấu hình và giới hạn của mã dùng lại: [Mã chuyển khoản TT/NT](MA-CHUYEN-KHOAN-TT-NT.md). Tài liệu chính thức: [cấu hình mã SePay](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan).
