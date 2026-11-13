# Thiết kế 0.4 — ECC/AES V3 và kết quả tài chính

Ngày 05/11/2026. Nguồn hiện hành: StudentCardApplet, CardClient, CardService, CardSynchronizer, ReaderHub, PaymentCoordinator, StationSessions, RefundStore, FinancialRetention và migration V4. Mật mã lưu trữ vẫn scheme 3; capability/STATE/snapshot mới là 4.

## Ranh giới

Máy quầy/đầu đọc/backend/APDU được tin cậy. Backend kiểm tra tài khoản admin, đăng ký public key, lifecycle, phiên sinh viên, số lần PIN sai và trạng thái nghiệp vụ. Thẻ thực hiện ECC, KDF, kiểm tra PIN, AES-CBC/HMAC, số dư, version và active slot.

ECDSA chỉ chứng minh thẻ giữ private key tương ứng public key đã đăng ký; không cấp quyền admin. Backend không ký từng APDU. Không có issuer, RSA, OAEP, ECDH hoặc ECIES.

## Nơi lưu

| Backend | Thẻ bền vững | RAM thẻ |
|---|---|---|
| Hồ sơ/ảnh bản rõ + pending riêng | EC private/public key, S_card | K_root, K_pin_enc/mac, K_data_enc/mac |
| Public key đăng ký, S_backend riêng | Hai slot PIN record và profile record | Buffer PIN/hồ sơ được xóa sau xử lý |
| lifecycle, pin_failures, pin_attempts | IV/tag/độ dài/version/counter/số dư | Quyền tạm và ngữ cảnh phiên |
| Mirror ví, snapshot ciphertext, nhật ký | Metadata PREPARE/COMMIT/ACK và debit hold | Engine mật mã và staging APDU |

S_backend không nằm trong DTO public hoặc snapshot mô phỏng. PIN record/private key/S_card/khóa dẫn xuất không có APDU xuất. Snapshot host dùng allowlist, chỉ chứa trạng thái bền vững; file này là mô phỏng bộ nhớ thẻ nên vẫn chứa private key và S_card, không phải một API nghiệp vụ.

## Lịch khóa

F(K,label,context,L) là L bit đầu của HMAC-SHA256(K, BE32(1) || ASCII(label) || 00 || context || BE32(L)).

- Root: F(S_card, TTM3/ROOT, cardId16 || BE32(1) || S_backend32, 256).
- PIN AES: F(root, TTM3/PIN/ENC, cardId16, 128).
- PIN MAC: F(root, TTM3/PIN/MAC, cardId16, 256).
- Profile AES: F(root, TTM3/DATA/ENC, cardId16 || BE32(cryptoVersion) || PIN6, 128).
- Profile MAC: F(root, TTM3/DATA/MAC, cùng context hồ sơ, 256).

Hai bí mật và keypair cố định; đổi PIN tăng cryptoVersion, tạo khóa hồ sơ mới, tái mã hóa hồ sơ. Khóa PIN vẫn từ hai bí mật. AES/HMAC RAM dùng TYPE_*_TRANSIENT_DESELECT; secret S_card dùng HMAC key persistent.

KDF theo cấu trúc counter mode của [NIST SP 800-108](https://csrc.nist.gov/pubs/sp/800/108/r1/upd1/final). Test CardCryptoTest tính độc lập bằng Java SE rồi đối chiếu ciphertext/tag do applet tạo.

## Bản ghi

AES-128-CBC/NoPadding primitive, applet thêm padding 80 rồi 00. IV ngẫu nhiên 16 byte mới cho mỗi lần ghi. Verify HMAC trước giải mã/padding; MAC bao phủ loại bản ghi, ID, scheme/version, độ dài, IV, ciphertext. Không dùng lại AES key làm MAC key.

Profile export = header77 || ciphertext. Header: scheme3 (1), cardId (16), profileVersion (4), cryptoVersion (4), IV (16), plaintextLength (2), ciphertextLength (2), HMAC (32). MAC = HMAC(profile_mac, 02 || header[0..44] || ciphertext).

PIN record nội bộ = ciphertext16 + IV16 + tag32; tag dùng header40 gồm type01, scheme03, cardId16, cryptoVersion4, IV16, plaintextLength2(6), rồi ciphertext16. Không export PIN record.

Hai slot chứa cả hồ sơ và PIN. PREPARE hoàn tất candidate ở slot inactive, COMMIT chuyển active + balance/version/counter trong JCSystem transaction, ACK dọn old slot. ABORT không thay active.

## ECC

Thông điệp ký: ASCII TTM3-AUTH (9) || purpose1 || cardId16 || cardSessionId16 || R32 || operationContext8.

Purpose 0: đăng ký thẻ trắng; 1: kết nối quản lý; 2: hóa đơn; 3: đối soát. Nonce mới từ SecureRandom, verify đồng bộ trong cùng yêu cầu. Signature ECDSA/SHA-256 DER có độ dài thay đổi. Backend chỉ dùng public key đã lưu; không tự thay khóa khi verify sai.

Cache xác thực quản lý ràng buộc ID, fingerprint public key đăng ký và cardSessionId. Thanh toán tạo proof mới gắn invoiceId; bước prepare và pay của cùng invoice/connection tái sử dụng ngữ cảnh đã xác thực. Đổi connection, hủy, khóa hoặc hoàn tất xóa ngữ cảnh thanh toán.

## APDU

CLA 80; các số dưới đây là hex. AID F0 54 54 4D 01 01.

| INS | Chức năng |
|---|---|
| 10 | STATUS: initialized, role tạm, ID, counter, profileLength. Số lần sai lấy ở backend |
| 12 | Public key P-256 dạng uncompressed point65 |
| 13 | PROVE: P1=purpose; data=R32 || context8; response=độ dài proof |
| 14 | Đọc proof theo offset P1/P2 |
| 20 | VERIFY_PIN: 6 chữ số, trả 01 đúng / 00 sai; lỗi crypto là SW6984 |
| 21 | Xóa quyền và khóa RAM |
| 22 | Đọc hồ sơ plaintext theo offset sau quyền hợp lệ |
| 23 | Đọc số dư cho phiên sinh viên đã xác thực |
| 24 | Nạp cardId16 || S_backend32; P1=00 bước PIN, 02 admin |
| 50 | INFO capability 4, cardSessionId và fingerprint EC |
| 52 | BEGIN lệnh có payload theo header63 |
| 53 | CHUNK: offset2 || payload phần tiếp theo |
| 54 | EXECUTE: PREPARE/COMMIT/ACK/ABORT/STATE/EXPORT/RELEASE |

Header63 = scope1 || cardId16 || operationId8 || offset2 || readLength2 || payloadLength2 || operationToken32. Token là ngẫu nhiên độc lập với PIN; không ghi digest cho yêu cầu chứa PIN vào DB. Đường APDU tin cậy nên header này không phải signed grant.

Request nghiệp vụ = kind1 || beforeBalance4 || nextProfile4 || nextCrypto4 || nextWallet4 || data.

| kind | data |
|---|---|
| 1 INIT | PIN6 || ProfileCodec |
| 2 UPDATE | ProfileCodec |
| 3 RESET_PIN | PIN mới6, admin |
| 6 CREDIT / 7 DEBIT | walletOperationId8 || amount4, sinh viên |
| 8 CHANGE_PIN | PIN mới6, sinh viên đã xác thực |
| 9 REVERSAL | originalCardOperationId8 || amount4; chỉ debit hold đủ điều kiện, không yêu cầu PIN mới |

STATE là 288 byte TTS4, unsigned. Các offset 0..266 giữ bố cục cũ; 267=hold (0 rảnh/1 debit chưa quyết toán/2 đã đảo); 268..275=debit card-operation ID; 276..279=B0; 280..283=amount; 284..287=walletVersion sau debit. Các phần trước chứa fingerprint, ID, balance/version, phase/pending/last-operation/last-ACK và hash bản mã. CardClient kiểm tra kích thước, domain, fingerprint, readId. Đây là đối chiếu trên đường tin cậy, không phải attestation ký ECC.

Scope EXECUTE: 01 PREPARE, 02 COMMIT, 03 ACK, 04 ABORT, 05 STATE, 09 EXPORT, 0A RELEASE. RELEASE dùng operationId của debit gốc, payload rỗng; chỉ dọn hold, không đổi số dư. Phân biệt kind09 REVERSAL với scope09 EXPORT.

## Backend PIN policy

STARTED attempt được lưu trước APDU. Kết quả hợp lệ được chốt một lần dưới card row lock: WRONG tăng đến 5 rồi BLOCKED/PIN_FAILURES; OK reset 0 nếu vẫn ACTIVE. Admin block thắng PIN đúng. Duplicate attemptId không tăng lặp; attempt của terminal/connection/purpose khác bị từ chối. Mất phản hồi hoặc lỗi truyền => UNKNOWN/không cấp phiên, không đoán đúng/sai.

Backend chỉ mở studentSession sau khi DB chốt PIN_OK. Quyền trên thẻ và quyền RAM backend đều cần hợp lệ. Bộ đếm PIN là transaction riêng, không rollback theo thanh toán thất bại. RESET_PIN admin tại DB_COMMITTED reset counter và đặt pin_counter_reset_applied trong cùng transaction; replay không xóa lỗi phát sinh sau đó. Lifecycle không tự mở.

## Trạng thái nghiệp vụ và transaction

Cards.status giữ PROVISIONING/ACTIVE/PENDING_UPDATE cho hồ sơ. lifecycle_status riêng ACTIVE/BLOCKED; sync_status riêng SYNCED/PENDING/CONFLICT. Thẻ bị khóa vẫn đối soát operation đã có quyết định commit; không bắt đầu nghiệp vụ mới.

Coordinator:
1. Lưu pending bản rõ, tạo operation CREATED.
2. PREPARE inactive, đối chiếu candidate và trạng thái, lưu CARD_PREPARED.
3. Kiểm tra lại quyền/lifecycle, khóa card row và hóa đơn; kiểm tra hạn trước COMMIT_DECIDED bền vững.
4. Card COMMIT; đối chiếu counter/version/balance/token/cipher hash.
5. DB transaction cập nhật hồ sơ active, mirror ví, snapshot, ledger và DB_COMMITTED.
6. Card ACK, kiểm tra lastAck; DB chốt SYNCED và topup tương ứng. SYNCED của DEBIT chưa tự chốt hóa đơn PAID.

Không giữ SQL transaction qua APDU. Nếu lỗi trước quyết định, abort ngay khi có thể; mất thẻ thì phục hồi khi cắm lại. Nếu lỗi sau quyết định, finish cùng operationId, kể cả thẻ đã bị admin khóa/QR hết hạn. DB không ghi trạng thái tiền tùy ý để “khớp” dữ liệu hỏng.

Giới hạn transaction được mô tả trong [PostgreSQL ROLLBACK](https://www.postgresql.org/docs/current/sql-rollback.html) và [Java Card JCSystem](https://docs.oracle.com/en/java/javacard/3.1/jc_api_srvc/api_classic/javacard/framework/JCSystem.html). Không thể cam kết rollback lập tức cả hai nơi khi thẻ đã commit rồi bị rút.

## Hóa đơn, phiên quầy và hoàn bù

StationSessions lưu stationId ổn định, session UUID mới, epoch, heartbeat và lease. UUID phiên cũng là X-Terminal-ID của checkout. Open idempotent theo station/startup; mở mới đóng session cũ đúng quầy. Closed/expired session không hồi sinh bằng heartbeat. Chu kỳ client 5 giây, lease server 15 giây; backend restart đóng session cũ.

Mỗi invoice có một payment_attempts row (invoice PK), gắn connection. OPEN → PAYMENT_PENDING khi bắt đầu xác thực. PIN sai/FUNDS/ECC/hủy PIN hủy hóa đơn; retry request chỉ đọc outcome, không tạo attempt mới. Bộ đếm PIN qua nhiều hóa đơn vẫn được giữ. Close ghi cancel trong SQL trước khi đợi reader; heartbeat độc lập EDT/hộp PIN.

PaymentCoordinator.debit ghi B0/version và liên kết debit, chạy giao thức thẻ. ACK debit giữ financialHold=1 trên applet. Sau khi cả hai phía xác nhận, SQL theo thứ tự session → card → invoice → attempt/operation chọn một outcome:

- SUCCESS: PAID bền vững, sau đó RELEASE dọn metadata idempotent. Nếu mất phản hồi sau PAID, vẫn PAID.
- CANCEL: trước commit thì ABORT; sau commit phải xác định/hoàn tất debit gốc rồi REVERSAL, xác nhận thẻ/mirror về B0, RELEASE và CANCELLED.
- Chưa đủ bằng chứng hoặc thiếu thẻ: CANCEL_PENDING, chặn nghiệp vụ mới và yêu cầu đúng thẻ để đối soát. Không báo đã hoàn khi chưa sửa được thẻ.

REVERSAL có wallet operation riêng với parent_id UNIQUE và kind9 card operation riêng. Amount/id/version phải khớp debit hold; card cho phép recovery không PIN mới trên đường tin cậy. Snapshot allowlist giữ hold/debit metadata; RAM/PIN/khóa dẫn xuất không được phục hồi như phiên cũ. Version/counter tăng theo thao tác, ledger giữ debit + reversal. Không dùng CREDIT tùy ý để sửa số dư.

Snapshot TTM3/TTM4 được deserialize bằng allowlist tương ứng, bổ sung metadata còn thiếu và ghi TTM5; identity/PIN/keys và debit hold hiện có giữ nguyên. TTM5 bổ sung cache hash bản mã, không lưu buffer/PIN/khóa phiên. File không nhận dạng được giữ lại và báo lỗi. Legacy debit chưa rõ, không có hold đủ bằng chứng, giữ CANCEL_PENDING/SYNC_CONFLICT để review. Không xóa thẻ hoặc ghi đè balance để vượt lỗi.

Applet tối ưu dùng 1.249 byte payload mảng transient, xử lý hồ sơ theo block và giữ hai slot ciphertext tối đa 28.016 byte/slot cho thẻ mới. READ_PROFILE kiểm tra HMAC/padding trước khi trả bản rõ rồi giải mã phần được yêu cầu; đổi/reset PIN tái mã hóa theo khối. Biến phiên được đặt trong transient; đường tiền không ghi/xóa lại toàn vùng ảnh. Capability 4, STATE TTS4 và record scheme 3 giữ nguyên. [Kết quả CAP và kiểm chứng](KIEM-CHUNG-APPLET-J3R180.md).

## Lifecycle và hard delete

Block ghi DB trước rồi vô hiệu hóa phiên. Restore cần admin + thẻ kết nối + ECC + settled, reset bộ đếm, giữ các giá trị trên thẻ.

Delete preview đọc STATE/balance thật trước, rồi đếm N khoản chờ. Token HMAC gắn card/connection/counter/walletVersion/balance và fingerprint item/amount/status/receipt. Nếu có số dư cần confirmBalance; sau đó nếu N>0 cần confirmUnsynced. Execute recheck dưới card lock, khóa invoice trước attempt để giữ thứ tự xóa.

RefundStore.archive và DELETE đăng ký trong một SQL transaction. Giữ refund_cases/items tối thiểu cho các khoản chờ; retired_topup_references giữ mã cũ chống ghép nhầm; bank_events giữ provider/bank-reference dedup và gỡ FK active card/topup. Không giữ PIN/S_backend/S_card/AES/private key/raw snapshot/ảnh/hồ sơ đầy đủ trong kho tài chính. Xóa card/secret/profile/pending/attempts/mirror/snapshots và dữ liệu riêng khác; file dọn sau commit. Hủy hoặc SQL lỗi giữ đăng ký/chứng từ nguồn nguyên vẹn.

Callback khóa card → topup trước khi nhận sự kiện gắn thẻ. Nếu card bị xóa trong lúc chờ lock, tra lại retired reference và ghi vào refund item; không nạp thẻ mới cùng MSSV, không tái tạo card. WAITING_BANK chỉ thành khoản cần hoàn khi có xác nhận tiền vào. Lưu/tra cứu phục vụ đối chiếu, chưa tự chuyển khoản hoàn ngân hàng.

## Nâng cấp trước V3

LocalConfig gọi FinancialRetention.beforeMigration trước Flyway: chỉ schema hiện hành; giữ khoản chờ, detach FK, commit và lưu fingerprint tài chính. Callback BEFORE_EACH_MIGRATE ngay trước V3 khóa các bảng trong transaction V3 và kiểm tra fingerprint; thay đổi sau preflight làm dừng upgrade. Phải dừng backend/worker/quầy cũ trước nâng cấp. Không sửa V1/V2/V3 đã áp dụng; V4 bổ sung các bảng/ràng buộc mới. Thẻ V3 đã được tạo không nằm trong quyền xóa bộ V2. Các failed debit cũ giữ liên kết ở legacy_invoice_id trước unique index DEBIT mới.

## Nạp tiền SePay (06/11/2026)

Migration V5 thêm mapping mã `TTM` + 6 chữ số MSSV sang thẻ gốc và các yêu cầu nạp riêng. Backend lấy ảnh VietQR.app cho BIDV VA `96247Y6CZZ`; POST `/webhook` xác thực API Key, đối chiếu `gateway`, `subAccount/accountNumber`, `transferType`, số tiền và thời gian ngân hàng. ID/reference SePay có namespace và unique constraints chống lặp. Webhook chỉ chốt SQL BANK_RECEIVED; worker hiện có ghi tiền lên thẻ qua cùng giao thức đồng bộ. Mapping tối thiểu giữ qua hard delete để callback muộn tìm chứng từ, không gắn thẻ mới cùng mã.

Ngrok Docker dùng domain hiện có, Host upstream cố định và backend filter chỉ công bố POST `/webhook`; giữ server loopback và tắt request inspection. `02-Backend.ps1` chuẩn bị cấu hình và khởi động tunnel sau health. API Key và ngrok authtoken nằm trong `.local`, không đưa vào mã nguồn. Xem [runbook nạp tiền SePay](NAP-TIEN-SEPAY.md) để cấu hình Dashboard và kiểm tra giới hạn của mã dùng lại.

## Nghiệm thu hiện hành

jCardSim là đích nghiệm thu. RAM stage/work gần 60KB và các object runtime vẫn cần tối ưu thêm nếu chuyển sang phần cứng RAM nhỏ; ECDSA không thay thế việc đo tài nguyên. Không có chuyển đổi ciphertext V2; bộ demo mới dùng scheme3. Bằng chứng chạy hiện tại và phần bị môi trường chặn nằm tại KIEM-CHUNG.md.


## Thực đơn và thanh toán hai phương thức (07/11/2026)

V6 chuẩn hóa danh mục và ảnh món trong PostgreSQL. Xóa danh mục cascade xuống món/ảnh; invoice_lines có khóa dòng riêng và item_id nullable ON DELETE SET NULL. Tên/giá/số lượng lúc bán giữ nguyên. MenuService dùng khóa catalog ngắn trong transaction, revision danh mục/món và requestId; tạo hóa đơn cùng khóa để chốt giá nhất quán. Mutation yêu cầu device key và phiên quầy ACTIVE, không yêu cầu admin.

V7 cho hóa đơn chưa gắn sinh viên, chống tạo lặp theo session/requestId, lưu mã CTHD và tách payment_phase khỏi outcome. CARD_AUTH chưa có debit nên ngân hàng được thắng. PaymentCoordinator ghi CARD_PROCESSING cùng wallet operation trước APDU; webhook tới sau đó lưu PENDING_CARD, không ghi thành công lần hai. PAID/CARD hoặc CANCELLED đã rõ sẽ đưa khoản nhận thêm vào REVIEW. Không tự nhả quyền debit khi chưa biết kết quả.

invoice_payment_references và bank_events giữ chứng từ tối thiểu sau xóa hóa đơn/thẻ. Trigger giữ outcome/phương thức và FK SET NULL tách liên kết hóa đơn. Biên nhận ngân hàng có một đích topup hoặc invoice; unique index chặn hai biên nhận MATCHED_INVOICE cho một mã. Endpoint webhook và cơ chế xác thực SePay hiện có được giữ. Worker quét PENDING_CARD phục hồi từ DB; không đợi APDU trong webhook/SQL transaction.

Desktop giữ polling hóa đơn trong lúc mở PIN và khi thao tác khác đang bận, bỏ phản hồi cũ theo revision/ID/phiên. Màn hình khách dùng hai cột, nút thanh toán chủ động và cùng connection có thể dùng cho hóa đơn mới với ECC/PIN mới. QR hóa đơn lấy từ ngân hàng, cache theo tham chiếu; không tạo QR ttm:// cho luồng trả tiền mới. Xem [hướng dẫn quầy](QUAY-THUC-DON-VA-THANH-TOAN.md).


## Mã sinh viên TT/NT và hiệu lực webhook V8 (lịch sử, thay bằng V9)

Đặc tả V8 từng thay mã public TTM/CTHD của V5/V7 bằng `(?:TT|NT)(?:AT|CT|DT)[0-9]{6}`. TT là thanh toán, NT là nạp tiền. MSSV bắt buộc khi tạo hóa đơn; backend tra sinh viên đã đăng ký. Các reference nội bộ vẫn duy nhất và được giữ nguyên để không thay danh tính chứng từ lịch sử.

BankMemos khóa advisory theo toàn bộ memo trước các khóa session/card; chỉ một cửa sổ còn mở cho mỗi loại của một sinh viên. Có chốt sang giây kế tiếp khi dùng lại mã vì transactionDate của SePay có độ phân giải giây. Chọn cửa sổ theo thời điểm giao dịch, không chỉ yêu cầu mới nhất; nếu cửa sổ nhập liệu bị chồng, trả REVIEW. Sau khi khóa đối tượng đích, lấy clock_timestamp() để kiểm tra hạn thực tại lúc xử lý. Đúng thời gian ngân hàng nhưng callback đến sau hạn cũng không ghi tiền.

closed_at của cửa sổ được lưu cùng transaction khi topup rời WAITING_BANK, invoice có outcome, hoặc đối tượng bị xóa. V8 giữ nguyên các migration cũ và khoản BANK_RECEIVED/CREDITED, hết hiệu lực QR nạp cũ. Webhook hợp lệ về xác thực vẫn trả 200/success=true cho giao dịch không đủ điều kiện nghiệp vụ, giữ chứng từ REVIEW; sai xác thực/tài khoản/payload vẫn bị từ chối như trước. Các khoản đã chấp nhận không bị ghi lần hai khi replay sau hạn. CARD_PROCESSING tiếp tục chờ kết quả thẻ theo quyền xử lý đã lưu trước APDU.

Cấu hình Dashboard và giới hạn mã dùng lại: [Mã chuyển khoản TT/NT](MA-CHUYEN-KHOAN-TT-NT.md).

## Mã riêng từng yêu cầu V9 (07/11/2026)

Mẫu hiện hành: TT/NT + MSSV đầy đủ + 8 số ID hóa đơn/phiếu nạp, đúng 18 ký tự. Backend cấp ID từ sequence (1..99.999.999), không quay vòng; unique index giữ mã sau hard delete. V1–V8 giữ nguyên. V9 mở rộng trường mã và hết hiệu lực topup WAITING_BANK dùng mẫu cũ; không thay bank_events đã nhận hay journal phục hồi.

BankMemos.find tra equality theo toàn bộ mã, không theo MSSV/giá/thời gian mới nhất. Thời gian ngân hàng và clock_timestamp() sau khóa chỉ kiểm tra hiệu lực yêu cầu đích. Không còn guard cửa sổ theo sinh viên hoặc khoảng chờ một giây. Retry cùng request giữ mã; hóa đơn/phiếu mới có mã khác, kể cả cùng số tiền.

Giữ memo lock trước card/session để tuần tự hóa webhook lặp; các khóa invoice/card và quyền CARD_PROCESSING tiếp tục phân xử với thanh toán thẻ. Callback bằng QR cũ chỉ gắn chứng từ cũ, không tác động yêu cầu mới. Mã cũ 10 ký tự còn trong DB để giữ chứng từ nhưng không được parser nhận mới; không tự rebind. Hướng dẫn [SePay và mã yêu cầu](MA-CHUYEN-KHOAN-TT-NT.md).
