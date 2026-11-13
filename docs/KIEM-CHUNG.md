# Bằng chứng kiểm chứng 0.4

## Tối ưu applet và full suite — 06/11/2026, 23:03

Đã triển khai streaming hồ sơ/PIN, giảm payload mảng transient từ 58.753 xuống 1.249 byte, giữ ảnh 24 KiB/hồ sơ 28.000 byte và các bất biến tiền. CAP qua converter/verifier Classic 3.0.4, có debug symbols; full suite một lượt đạt **174 backend + 5 GUI = 179 ca, 0 failures/errors/skipped**, gồm PostgreSQL/HTTP trên `ttm_test`. Có fixture từ applet cũ để kiểm tra nhập snapshot TTM3/TTM4, giữ hồ sơ/khóa/PIN/số dư và debit hold; bản mới ghi TTM5.

Chi tiết artifact, lệnh và giới hạn trong [KIEM-CHUNG-APPLET-J3R180.md](KIEM-CHUNG-APPLET-J3R180.md). Người dùng xác nhận chưa có thẻ thật; chưa công nhận RAM native, tổng NVM, hiệu năng hay mất nguồn vật lý trên J3R180. Các mục cũ bên dưới là lịch sử tại thời điểm được ghi; kết quả full suite mới này thay thế giới hạn full suite cũ cho phiên bản nguồn hiện tại.

Ngày 05/11/2026. Project C:/JavaCard/TTM_CuoiKy. Mật mã vẫn scheme V3; capability/APDU STATE và snapshot mới là 4.

## Đã kiểm chứng tiền thật và xử lý 401 — 06/11/2026, 18:37

Người dùng chuyển khoản thật 10.000đ vào VA, mã TTM070352. SePay Live nhận giao dịch lúc 18:22:15; lần webhook đầu 18:22:16 bị 401 SEPAY_AUTH. Kiểm tra trực tiếp Dashboard thấy webhook vẫn để **Không xác thực**; request log chỉ có Content-Type. Probe id=0 qua local/ngrok với khóa project đều 200, thiếu khóa đều 401; file khóa và cấu hình backend khớp, backend đã nạp đúng cấu hình. Nguyên nhân là phương thức xác thực trên SePay chưa được lưu, không phải mất header qua tunnel.

Người dùng đã tự nhập khóa từ `.local/sepay-webhook-api-key.txt`, chọn API Key và bấm Cập nhật; Dashboard hiển thị API Key và Sửa thành công. Người dùng phát lại chính giao dịch gốc. Nhật ký SePay lúc **18:37:40 trả HTTP 200 OK**. Không tạo giao dịch ngân hàng mới và không cộng số dư bằng SQL.

Đối chiếu backend sau phát lại: topup 1 = CREDITED, amount 10000; receipt SePay = MATCHED, gắn đúng topup; đúng 1 CREDIT APPLIED trị giá 10000; card_wallets.balance = 10000, wallet_version = 1; card operation kind 6 = SYNCED, before_balance 0, next_balance 10000. Như vậy luồng tiền thật SePay → ngrok → backend → PREPARE/COMMIT/ACK trên thẻ mô phỏng đã được kiểm chứng. Chưa có bằng chứng thẻ vật lý/CAP.

Bằng chứng cục bộ đã che khóa: `.local/verification-sepay-live.json`. Không đổi mã nguồn sản phẩm, không restart backend và không chạy lại build/test suite cho lỗi cấu hình này. Kết quả thay thế giới hạn “chưa chuyển tiền thật” được ghi tại thời điểm triển khai bên dưới; giữ phần cũ làm lịch sử.

## SePay/ngrok đã triển khai ngày 06/11/2026

- Backend đã được đóng gói và khởi động lại; health trả UP, protocol=4, bankWebhook=sepay-v1, bankProvider=sepay. PostgreSQL ứng dụng đã áp dụng V5; giữ 1 đăng ký thẻ hiện có. Trước triển khai đã dừng đúng backend, backup dump PostgreSQL, JAR, cấu hình và file thẻ vào `.local/backups/sepay-upgrade-20261106-180510/`; cấu hình trước khi bật SePay còn ở `.local/backups/sepay-before-config/`.
- Container `ttm-cuoiky-ngrok` chạy image chính thức 3.39.13 pin digest, dùng domain hiện có, upstream `host.docker.internal:18080`. `02-Backend.ps1` tự cấu hình/khởi động tunnel sau health; không cần receiver WebhookTest ở cổng 3000. Đã kiểm tra Docker có thể nối backend loopback trên Windows này.
- `Verify-SePayWebhook.ps1` qua Internet: POST thiếu API Key → 401; probe id=0 có API Key → 200 `success=true`; GET `/api/health` qua ngrok → 404. Số dòng cards/bank_events/topups/wallet_operations trước/sau không đổi. Bằng chứng: `.local/verification-sepay-public.json`. Người dùng xác nhận đã lưu cùng API Key trên Dashboard SePay; đây là xác nhận cấu hình, chưa phải bằng chứng callback tiền thật.
- Gọi chính BankGateway mới tới VietQR.app và giải mã ảnh PNG bằng ZXing: bank BIN 970418, VA 96247Y6CZZ, amount 1000, memo TTM070352 và CRC đều khớp. Không chuyển tiền. Bằng chứng: `.local/verification-sepay-qr.json` và ảnh `.local/verification-sepay-qr.png`.
- 18 ca mới đạt: SePayGatewayTest 7, SePayFlowTest 8, SePayHttpFlowTest 1, SnapshotPersistenceTest 2. Có kiểm tra xác thực, sai ngân hàng/VA/số tiền, tiền ra, replay, worker tự ghi thẻ, rút thẻ/nhập PIN lại, callback cũ khi mã dùng lại, mã trùng giữa chương trình, đổi MSSV, xóa thẻ và refund, cửa sổ cùng giây/hết hạn, payload quá lớn, file Windows bị khóa tạm thời/lâu dài.
- Đã chạy full suite **một lượt**: 146 ca backend, 9 failures, 0 errors, 0 skipped; UI bị Maven bỏ qua sau backend failure. Chín lỗi thuộc các assertion/setup cũ: đếm cả PC/SC khi chỉ kiểm tra simulator; dùng lại phiên quầy qua restart; sửa S_backend nhưng dùng lại ngữ cảnh RAM cũ; kiểm tra chuỗi thông báo thay vì kết quả phục hồi PIN. Đã sửa các test để xác minh đúng hợp đồng hiện hành, giữ kiểm tra PIN thật/version/mirror, rồi chạy lại đúng các ca lỗi: 8 ca đạt trong lượt targeted, ca crypto còn lại đạt sau sửa expectation về bước unlock. **Không chạy lại full suite lần hai; không ghi nhận một lượt full-suite xanh mới.**
- Chạy riêng 5 LayoutSmokeTest sau khi UI bị bỏ qua: đạt. Đóng gói production một lượt `mvn -o -f build/pom.xml -DskipTests package`: BUILD SUCCESS. Bỏ test trong bước package không được dùng để tuyên bố full suite đạt.
- Phép tái hiện file Windows độc lập thấy 1/250 atomic moves bị AccessDenied, thử lại sau 100 ms thành công. ReaderHub giữ atomic replace và chỉ retry một lần; khóa kéo dài vẫn lỗi, không báo đã lưu giả. Các ca SePay ban đầu lỗi ở bước này đã chạy lại đạt sau sửa.

Logs: `.local/verification-sepay-full.log`, `verification-sepay-legacy-targeted.log`, `verification-sepay-pin-context.log`, `verification-sepay-ui.log`, `verification-sepay-package.log`. Chi tiết vận hành và Dashboard: [NAP-TIEN-SEPAY.md](NAP-TIEN-SEPAY.md).

**Chưa kiểm chứng:** chuyển tiền ngân hàng thật và callback SePay Live dẫn đến CREDITED trên thẻ đang dùng; thẻ vật lý/CAP. Probe công khai và fixture ttm_test không thay thế bằng chứng tiền thật. Các mục 05/11 bên dưới được giữ làm lịch sử, không phải trạng thái triển khai hiện tại.

## Cập nhật hạ tầng ngày 06/11/2026

CSDL đã được chuyển và đối chiếu sang Docker Desktop; kết nối JDBC bằng chính cấu hình backend hiện đã đạt, gồm sửa tương thích timezone Asia/Saigon tại image. Xem [bằng chứng và cách dùng Docker](CSDL-DOCKER.md). Các lỗi kết nối trong lượt full suite ngày 05/11 bên dưới là bằng chứng lịch sử; chưa chạy lại full suite/HTTP trong lần chuyển CSDL, nên chưa nâng trạng thái nghiệm thu nghiệp vụ.

## Kết quả hiện tại

**44 ca không phụ thuộc PostgreSQL đã đạt: 0 failures, 0 errors, 0 skipped trong các nhóm này.** Đã biên dịch mã sản phẩm và toàn bộ mã kiểm thử, đóng gói jar mới. Chạy simulator bằng chính backend jar: ECC isolation và persistence đều PASS.

**Full suite chưa đạt.** Một lượt `mvn -o -fae -f build/pom.xml test` ghi nhận 6 nhóm SQL/HTTP lỗi ở bước kết nối: `java.net.SocketException: Permission denied: getsockopt`. Các assertion nghiệp vụ trong nhóm bị lỗi setup chưa chạy. Không thay PostgreSQL bằng DB giả để đánh dấu đã đạt.

Mã nguồn 0.4 đã có phiên quầy, một attempt/hóa đơn, hủy/hoàn bù, giữ khoản nạp chờ khi xóa, reset counter PIN đúng một lần, preflight trước V3 và migration V4. **Chưa áp dụng thành công migration vào ttm_cantin; chưa tạo bộ demo mới hoặc xác nhận xóa thẻ trong DB ứng dụng.** Không dùng kết quả 38 ca V3 cũ làm bằng chứng cho phần transaction 0.4.

## Nhóm đã thực thi

| Nhóm | Số ca | Phạm vi đã chứng minh |
|---|---:|---|
| AppletFlowTest | 10 | ECC, PIN/secret, quyền đọc, snapshot/RAM và từ chối APDU cũ |
| ProtocolFlowTest | 7 | Hai slot, commit/ACK/abort, đổi/reset PIN, dữ liệu/ví qua restart |
| CardCryptoTest | 11 | Vector KDF độc lập, AES-CBC/HMAC, IV, tái mã hóa khi đổi PIN, chống sửa record |
| ReversalAppletTest | 5 | ACK giữ debit hold; reversal sau restart không PIN mới; sai parent/amount bị từ chối; lặp không cộng hai lần; abort debit; nâng snapshot TTM3 giữ identity/PIN |
| ContractTest | 6 | Profile codec, Casso HMAC/retry-age, SVG và QR hóa đơn local |
| LayoutSmokeTest | 5 | Hai tab, QR/hết hạn, thu hồi form/dialog, phản hồi cũ; CANCEL_PENDING không được hiện như đã hủy hoặc bị OPEN cũ ghi đè |

Tổng 39 ca backend không SQL + 5 ca Swing = 44. Lượt full suite báo backend 45 mục gồm 39 ca đạt và 6 lỗi setup; không được diễn giải là 45 ca nghiệp vụ đã chạy. Sau khi xem ảnh phát hiện chồng chữ ở 960×660, đã sửa BoxLayout vùng hóa đơn và chạy lại riêng 5 ca Swing: đạt.

## Lệnh thực tế

~~~powershell
mvn -o -f build/pom.xml -pl :ttm-backend -am '-Dtest=ReversalAppletTest,ProtocolFlowTest,AppletFlowTest,CardCryptoTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o -fae -f build/pom.xml test
mvn -o -f build/pom.xml -pl :TTM_CuoiKy -am '-Dtest=LayoutSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o -f build/pom.xml -DskipTests test-compile
mvn -o -f build/pom.xml -DskipTests package
java -jar backend/target/ttm-backend-1.0-SNAPSHOT.jar --check-card-runtime
~~~

- Targeted applet/protocol: 33 ca đạt.
- Full suite: thất bại vì 6 nhóm không kết nối PostgreSQL; các nhóm độc lập ở bảng trên đạt.
- UI sau sửa layout: 5 ca đạt.
- Test-compile và package: BUILD SUCCESS. Package bỏ chạy test, không phải bằng chứng full suite đạt.
- Packaged runtime: SIMULATOR_ECC_ISOLATION PASS, SIMULATOR_PERSISTENCE PASS.
- Đối chiếu byte với bản trước sửa: V1/V2/V3 migration giữ nguyên, không đổi checksum.

Artifact:

- Desktop: target/TTM_CuoiKy-1.0-SNAPSHOT.jar.
- Backend: backend/target/ttm-backend-1.0-SNAPSHOT.jar.
- Applet: card/target/ttm-card-1.0-SNAPSHOT.jar.
- Logs: .local/verification-04-full.log, verification-04-ui.log, verification-04-compile.log, verification-04-package.log, verification-04-packaged-runtime.log.
- JUnit XML: backend/target/surefire-reports và target/surefire-reports.
- Đối chiếu jar/module/schema và SHA-256: .local/verification-04-artifacts.json.
- Diff so với nguồn trước đợt 0.4: .local/implementation-04-diff.txt; bản nguồn cũ chỉ để kiểm tra thay đổi, không phải backup dữ liệu thẻ.

## PostgreSQL và các ca chưa chứng minh được

pg_isready không nhận phản hồi ở 127.0.0.1:5548. Chuẩn bị DB gặp lỗi ghi postgres.log; lần khởi động có log riêng báo port đang được sử dụng. Lượt test JDBC xác nhận lỗi socket getsockopt. Không thay firewall, dừng tiến trình không xác định, đổi cổng DB hoặc tạo hạ tầng giả để lách lỗi.

| Nhóm | Trạng thái |
|---|---|
| BackendPolicyTest | ERROR ở setup PostgreSQL |
| DatabaseFlowTest | ERROR ở setup PostgreSQL |
| SynchronizationFlowTest | ERROR ở setup PostgreSQL |
| HttpFlowTest | ERROR ở kết nối trước khi khởi động HTTP test |
| InvoiceOutcomeTest | ERROR ở setup PostgreSQL; assertion tài chính chưa chạy |
| MigrationRetentionTest | ERROR ở setup PostgreSQL; assertion migration chưa chạy |

Mã regression mới đã biên dịch, bao gồm:

- Sai PIN qua nhiều hóa đơn vẫn khóa ở lần thứ 5; mỗi hóa đơn thất bại bị hủy, không thử lại.
- Admin reset counter 4→0, lỗi trước commit giữ 4, lỗi PIN mới không bị replay reset xóa.
- Rút thẻ sau debit → CANCEL_PENDING; kết nối lại → reversal một lần, hai số dư về B0; ngắt tại commit/DB/ACK/release của reversal.
- Close/lease/reopen đúng quầy; PAID trước mất phản hồi giữ PAID; SUCCESS/CANCEL có quyết định duy nhất.
- Xóa có tiền chờ, hai xác nhận, stale preview dù N không đổi, archive/delete rollback cùng nhau, callback muộn/trùng và callback chờ delete lock; thẻ mới cùng MSSV không nhận tiền của thẻ đã xóa.
- Giữ chứng từ trước V3, chặn V3 nếu fingerprint đổi sau preflight, retry preflight idempotent và không đổi checksum cũ.

Các fixture từ chối DB ngoài `jdbc:postgresql://127.0.0.1:5548/ttm_test` trước khi migrate/TRUNCATE/clean. Khi localhost truy cập được, dừng backend/quầy cũ, chạy scripts/Prepare.ps1 -Test và 00-Build.cmd trước khi dùng migration trên DB ứng dụng. Health của bản mới trả protocol=4; bản mã vẫn scheme 3.

## Bằng chứng UI và giới hạn

Ảnh render fixture, không phải giao dịch ngân hàng/thanh toán thật:

- target/ui-check/quan-ly-960.png.
- target/ui-check/quay-thanh-toan.png.
- target/ui-check/qr-960.png và qr-expired-960.png.
- target/ui-check/cancel-pending-960.png.
- target/ui-check/cancel-pending-customer-960.png.

Đã xem trực tiếp hai ảnh CANCEL_PENDING sau sửa layout ở 960×660: nội dung trạng thái không chồng nhau, tab khách không hiển thị QR hoặc tuyên bố đã hoàn tiền. Giữ Swing/FlatLaf và điều khiển native của project.

UNVERIFIED: thao tác hai cảnh báo xóa/refund table với DB thật; tổ hợp bàn phím và trình đọc màn hình; UI/HTTP end to end; các transaction/race CSDL; thẻ vật lý/CAP. jCardSim là mốc nghiệm thu applet đã chọn.

Debit legacy không có đủ metadata hold được giữ chờ review, không tự hoàn hoặc ghi đè balance. Chưa có chuyển khoản hoàn ngân hàng tự động. Khi đúng thẻ chưa kết nối, CANCEL_PENDING là trạng thái phục hồi được thiết kế; không thể tuyên bố đã hoàn số dư trên thẻ ngay khi thẻ bị rút.


## Danh mục/món và hóa đơn hai phương thức — 07/11/2026

Phạm vi mới được kiểm chứng trên PostgreSQL thật ttm_test, Spring HTTP và jCardSim. Tổng kết theo test case hiện hành, loại báo cáo cũ không còn source: **193 backend + 6 Swing = 199 ca**, không lỗi/skip ở các kết quả cuối. Đây là kết quả ghép một lượt hồi quy toàn project và các lượt có mục tiêu sau sửa; không tuyên bố đã chạy lại toàn bộ suite lần hai.

- Lượt hồi quy backend đầu: 193 ca, 192 đạt, một ca còn yêu cầu rút/cắm lại cho hóa đơn kế tiếp. Ca này được đổi đúng yêu cầu mới, kiểm tra cùng connection vẫn có ECC context mới và bắt buộc PIN. Lượt BackendPolicyTest + LayoutSmokeTest sau đó đạt.
- CatalogAndInvoicePaymentsTest: 16 ca, gồm CRUD/revision/xóa cứng, ảnh, lịch sử và giá, idempotency, khách không thẻ, replay/tiền dư, hủy/hết hạn, thẻ kết nối trước, bank trong CARD_AUTH/CARD_PROCESSING và sau lỗi debit/hoàn tác, webhook đồng thời, giữ chứng từ sau xóa hóa đơn.
- CatalogHttpFlowTest: HTTP thật cho device/session guard, nhân viên không admin, ảnh, lọc danh mục, lập hóa đơn khách và xóa cứng không mất dòng hóa đơn; endpoint đối soát vẫn yêu cầu admin.
- CatalogMigrationTest: tạo schema V5 có hóa đơn PAID và giá món đã đổi, nâng V6/V7, xóa danh mục; tên/giá lúc bán/tổng hóa đơn giữ đúng.
- SePayGatewayTest bổ sung CTHD 12 chữ số, cùng cấu hình nhận tiền, code null, mã topup/invoice lẫn nhau và code/content mâu thuẫn.
- LayoutSmokeTest: 6 ca. Có kiểm tra lọc danh mục, thẻ kết nối trước không tự bật PIN, nút thanh toán dùng được, hai dòng hóa đơn nhìn thấy ở 960×660, đóng PIN/bỏ QR khi bank PAID và giữ chống phản hồi cũ. Đã sửa phần bảng hóa đơn bị thu nhỏ và xem lại ảnh sau sửa.

Ảnh fixture đã xem: target/ui-check/catalog-960.png, payment-960.png, payment-1280.png. QR trong ảnh kiểm tra bố cục là dữ liệu fixture, không dùng chuyển tiền. Các ảnh này không chứng minh ứng dụng ngân hàng thật quét QR hay giao dịch SePay Live đã thanh toán.

Log trong .local: menu-payments-full-tests.log, menu-payments-policy-ui.log, menu-payments-ui-final.log, menu-payments-ui-labels.log, menu-payments-final-bank.log. Báo cáo kết quả được ghép theo class + test case, bỏ suite LegacySnapshotGeneratorTest đã không còn source; không cộng lặp các lượt chạy lại. Bản baseline trước chỉnh sửa nằm trong .local/menu-payments-before; không có Git checkout tại thư mục này.

UNVERIFIED còn lại: giao dịch ngân hàng Live và bộ lọc CTHD trên Dashboard thực; thẻ/đầu đọc vật lý; thao tác người dùng xuyên suốt desktop → ngân hàng thật; trình đọc màn hình và toàn bộ tổ hợp bàn phím. Không đổi applet/CAP trong chức năng này. Backend/CSDL ứng dụng đang chạy chưa được tự cập nhật.


## Mã TT/NT + MSSV và hiệu lực webhook — 07/11/2026 (V8)

Yêu cầu mới thay mã public TTM/CTHD bằng `(?:TT|NT)(?:AT|CT|DT)[0-9]{6}`. TT trả hóa đơn, NT nạp tiền; MSSV đầy đủ bắt buộc lúc tạo hóa đơn. Người dùng chọn webhook hết hạn/đã hủy vẫn HTTP 200/success=true, lưu REVIEW và không ghi tiền.

Đã chạy **một lượt full suite thành công: 209 backend + 6 Swing = 215 ca, 0 failures, 0 errors, 0 skipped** bằng `mvn -o -q -f build/pom.xml test`. Kết quả trong `.local/bank-memo-ttnt-full-tests.log`; dữ liệu test bắt buộc ttm_test. Các bước có mục tiêu trước đó: StudentBankMemoTest/SePayGatewayTest, các flow SePay/catalog/HTTP, nâng V7→V8 và ca cửa sổ chồng nhau. Một fixture cũ được sửa bank timestamp vào đúng đoạn thời gian chồng để giữ ý nghĩa ca kiểm thử, sau đó ca đó và full suite đạt.

- StudentBankMemoTest: 14 ca cho các chương trình AT/CT/DT; cùng sáu số khác chương trình; TT/NT không phân bổ chéo; thiếu MSSV; một cửa sổ đang mở theo mã, cả tạo song song từ hai quầy; callback đến sau hạn dù thời gian ngân hàng trước hạn; hủy; replay; dùng lại mã; callback cũ không trả yêu cầu mới; từ chối mẫu cũ.
- StudentMemoMigrationTest: nâng schema V7 có mã cũ/yêu cầu chờ/đã nhận/đã nạp và hóa đơn đã trả; V8 chỉ hết hiệu lực WAITING_BANK, giữ trạng thái đã nhận/đã nạp và reference/outcome lịch sử.
- SePayHttpFlowTest: request hết hạn nhận HTTP 200/success=true, topup EXPIRED, bank_event REVIEW và không tạo wallet operation.
- Các flow có sẵn được điều chỉnh theo mã đầy đủ, cho hai chương trình trùng sáu số và cửa sổ mới khi cấp lại thẻ; các ca PIN/debit/REVERSAL/RELEASE và UI hồi quy vẫn đạt.
- So sánh hash với bản trước: V1–V7 giữ nguyên, chỉ thêm V8. Applet/CAP không thay đổi.

Ảnh `target/ui-check/catalog-960.png` và `payment-960.png` được sinh lại trong lượt full suite; dùng dữ liệu và QR fixture. Cấu hình Dashboard Live và giao dịch tiền thật theo mã TT/NT chưa được thay/kiểm chứng trong lượt sửa mã này. Hướng dẫn hiện hành: [Mã chuyển khoản TT/NT](MA-CHUYEN-KHOAN-TT-NT.md).

Đóng gói cùng lượt thay đổi: `mvn -o -q -f build/pom.xml -DskipTests package` đạt trong source stage riêng, không thay JAR đang dùng. Gói [ttm-student-bank-memos-20261107.zip](../.local/bank-memo-ttnt-release/ttm-student-bank-memos-20261107.zip) gồm backend/desktop JAR, hướng dẫn và manifest SHA-256; đã kiểm tra CRC ZIP, hash file và V8 trong backend JAR. Bằng chứng tên/kết quả 215 ca: [test-evidence.json](../.local/bank-memo-ttnt-release/artifacts/test-evidence.json).


## Mã riêng hóa đơn/phiếu nạp V9 — 07/11/2026

- Mã mới đúng 18 ký tự: TT/NT + MSSV đầy đủ + 8 số ID hóa đơn/phiếu. Backend tra toàn bộ mã, không fallback theo MSSV/thời gian mới nhất. Tải lại QR/retry giữ mã; yêu cầu mới có mã mới, không cần chờ sang giây kế tiếp.
- V1–V8 giữ nguyên byte; thêm V9 mở rộng cột và unique index cho mã mới. V8 → V9 đã được thử trên schema riêng trong ttm_test với chứng từ cũ và khoản đã nhận; giữ BANK_RECEIVED/CREDITED, bank_events và tham chiếu lịch sử, hết hiệu lực WAITING_BANK dùng QR cũ. Applet/CAP không thay đổi.
- 27 ca tập trung SePayGatewayTest/StudentBankMemoTest/RequestMemoMigrationTest đạt, gồm QR cũ dùng cho giao dịch ngân hàng mới, mã sai sinh viên/không tồn tại, request ID không quay vòng, nhiều quầy cùng MSSV và retry giữ mã.
- Chỉ chạy một lượt hồi quy toàn bộ: 214 ca backend, 211 đạt và 3 fixture cũ lỗi (CatalogHttpFlowTest còn regex mã ngắn; SePayHttpFlowTest dùng mã ngắn; SePayFlowTest đặt hết hạn trước lúc mở sau khi bỏ cooldown). Cập nhật đúng ba fixture và chạy lại đúng ba ca: đều đạt. Sáu ca Swing vốn chưa chạy do reactor dừng tại backend được chạy riêng: đều đạt. Sau khi sửa số đuôi trong fixture ảnh cho khớp số hóa đơn, ca layout tương ứng được chạy lại và đạt.
- Kết quả gộp theo danh tính ca: **214 backend + 6 Swing = 220 ca đã đạt, không bỏ qua**. Đây là bằng chứng gộp lượt hồi quy và lượt sửa fixture; không tuyên bố có một full run xanh sau cùng. Không sửa runtime source sau lượt hồi quy.
- `mvn -o -q -f build/pom.xml -DskipTests package` đạt một lần trong stage riêng. Gói [ttm-unique-bank-requests-20261107.zip](../.local/bank-request-id-release/ttm-unique-bank-requests-20261107.zip) có backend/desktop JAR, tài liệu, manifest hash và [kết quả tên từng ca](../.local/bank-request-id-release/artifacts/test-evidence.json).
- Chưa áp dụng V9 vào CSDL vận hành, chưa thay JAR đang dùng, chưa sửa Dashboard hoặc nghiệm thu chuyển khoản Live mẫu mới. Cấu hình sáu tiền tố TTAT/TTCT/TTDT/NTAT/NTCT/NTDT, hậu tố số min=max=14 theo [hướng dẫn hiện hành](MA-CHUYEN-KHOAN-TT-NT.md).
