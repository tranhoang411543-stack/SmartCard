# Kế hoạch 0.4: ECC, hai bí mật, phiên quầy và đồng bộ giao dịch

Ngày cập nhật: 05/11/2026. Project: C:/JavaCard/TTM_CuoiKy. Bản kế hoạch: 0.4.

Trạng thái ngày 05/11/2026: mã nguồn 0.4 đã được triển khai theo các điều khoản bên dưới; 44 ca không phụ thuộc PostgreSQL đã đạt và các jar mới đã đóng gói. Sáu nhóm SQL/HTTP bị chặn ở kết nối getsockopt, chưa chứng minh transaction/migration bằng DB thật. Chưa áp dụng migration hoặc seed trên DB ứng dụng. Xem [KIEM-CHUNG.md](KIEM-CHUNG.md) cho bằng chứng và giới hạn hiện hành.

Kế hoạch 0.4 thay quy tắc thử lại PIN trên cùng hóa đơn bằng một attempt/hóa đơn; giữ khoản nạp chờ khi xóa; reset counter admin đúng một lần; hủy phiên quầy cũ; bổ sung debit hold/REVERSAL/RELEASE. Mật mã scheme 3 giữ nguyên, capability/state/snapshot là 4. Chi tiết ở [kế hoạch đồng bộ](KE-HOACH-DONG-BO-THE-BACKEND.md).

**1. Các quyết định của người dùng**

| Hạng mục | Quyết định dùng để triển khai |
|---|---|
| Nghiệp vụ | Giữ project sinh viên/thanh toán căn tin |
| Xác thực thẻ | ECDSA P-256/SHA-256 trước khi nạp bí mật backend và kiểm tra PIN |
| Khóa hồ sơ | Dẫn xuất từ S_card, S_backend và PIN; đổi PIN tạo khóa mới và tái mã hóa hồ sơ |
| Khóa bảo vệ PIN | Chỉ phụ thuộc hai bí mật, không phụ thuộc PIN |
| Hai bí mật | Mỗi bí mật ngẫu nhiên 32 byte; S_backend riêng, không trùng giữa các thẻ; cố định trong vòng đời thẻ |
| Khóa dẫn xuất | Chỉ ở RAM, không lưu bền vững trên thẻ hoặc CSDL |
| CSDL hồ sơ | Bản rõ cho quản lý/tìm kiếm/lọc/sắp xếp; bỏ AES-GCM backend |
| PIN đúng/sai | Applet kiểm tra; backend nhận kết quả và quản lý bộ đếm bền vững |
| Số lần sai | 5 lần sai liên tiếp; lần sai thứ 5 khóa thẻ; hủy hóa đơn không reset bộ đếm |
| Quầy quản lý | Sinh viên xác thực ECC + PIN một lần; xem hồ sơ/số dư, nạp tiền, đổi PIN trong phiên đến khi rút thẻ |
| Quầy thanh toán | Hai tab nhân viên/khách; ECC + PIN cho mỗi lần thanh toán; không tái sử dụng phiên cho hóa đơn khác |
| QR và kết quả thất bại | QR 5 phút; PIN sai/không đủ số dư hủy hóa đơn, lần sau phải tạo hóa đơn mới |
| Đóng/mở quầy | Backend kết thúc phiên cũ, hủy hóa đơn chưa thành công; không tiếp tục hóa đơn cũ khi mở lại |
| Admin đặt lại PIN | Thành công thì pin_failures=0 đúng một lần; không tự mở thẻ đang BLOCKED |
| Khôi phục thẻ | Admin mở lại thẻ đang khóa; thẻ đã xóa cứng không thể khôi phục |
| Xóa thẻ | Chỉ thẻ BLOCKED; kiểm tra số dư thực trước rồi khoản nạp chưa đồng bộ, hai cảnh báo/xác nhận riêng |
| Dữ liệu sau xóa | Xóa đăng ký/hồ sơ/bí mật, giữ riêng chứng từ nạp chưa đồng bộ để đối chiếu/hoàn tiền; không dùng để khôi phục thẻ |
| Thanh toán bị hủy | Kết quả cuối CANCELLED phải hết khoản trừ ròng ở cả DB và thẻ; đang đối soát/hoàn bù là CANCEL_PENDING |
| Dữ liệu cũ | Bỏ thẻ theo cơ chế cũ, tạo bộ demo mới; giữ khoản tài chính cần đối chiếu trước khi chạy migration xóa |
| Nghiệm thu | jCardSim và ứng dụng máy tính là đủ; không yêu cầu CAP/thẻ vật lý; các ràng buộc DB cần kiểm thử PostgreSQL thật |

Giả định đầu đọc, máy quầy, backend và đường APDU tin cậy được giữ nguyên. Không thêm RSA-OAEP, issuer ký lệnh, secure channel, ECIES hoặc ECDH để mã hóa đường truyền.

**2. Ranh giới và những giới hạn cần diễn đạt đúng**

- ECC xác thực thẻ với backend bằng public key đã đăng ký; admin vẫn cần đăng nhập đúng tài khoản, sinh viên phải qua PIN.
- Applet tin lệnh từ đầu đọc trong mô hình đề tài. Backend phải kiểm tra lifecycle/quyền/phiên ở mọi điểm vào, kể cả khi còn khóa RAM.
- Mã hóa hồ sơ/PIN chỉ trên applet. Backend nghiệp vụ không nhận S_card hoặc khóa AES qua APDU.
- File mô phỏng .card chứa S_card/private key ECC, DB chứa S_backend và hồ sơ bản rõ. Sao chép toàn bộ host nằm ngoài phạm vi bảo vệ đã chọn.
- Giữ khóa đến khi rút thẻ không có nghĩa giữ được RAM qua reset/mất điện/backend restart. Mất ngữ cảnh thì xác thực lại.
- Khóa/xóa thẻ là thu hồi quyền chủ động, được phép hủy phiên dù thẻ còn cắm; không phải timeout nhàn rỗi.
- Khoản nạp đã được ngân hàng nhận, journal và bộ đếm PIN sai là dữ liệu có thật, không xóa khi hủy hóa đơn để giả lập chưa từng thao tác.
- SQL và applet có transaction cục bộ riêng. Khi đã trừ tiền rồi mất kết nối, dùng đối soát/hoàn bù và CANCEL_PENDING; chỉ báo CANCELLED sau khi chứng minh số dư đã về nguyên trạng.
- PAID đã chốt bền vững trước lỗi hiển thị vẫn là thanh toán thành công; mất phản hồi không đủ để kết luận thất bại.
- Không chuyển khóa/ciphertext V2 sang V3. Yêu cầu giữ chứng từ tài chính trước khi bỏ V2 là ngoại lệ lưu trữ phục vụ hoàn tiền, không phục hồi thẻ.

**3. Vật liệu khóa và lịch dẫn xuất trên applet**

| Giá trị | Lưu bền vững | Khi hoạt động |
|---|---|---|
| EC keypair | Thẻ; public key có bản đăng ký CSDL | Ký challenge |
| S_card | Chỉ trong thẻ, không xuất APDU | Đầu vào KDF |
| S_backend | Bảng riêng trong DB theo cardId | Backend gửi xuống RAM applet sau kiểm tra ECC/quyền/trạng thái |
| K_root, K_pin_enc, K_pin_mac | Không | Giữ RAM trong phiên được phép |
| K_data_enc, K_data_mac | Không | Giữ RAM sau xác thực theo quyền của quầy |
| PIN plaintext | Không lưu DB hoặc trạng thái thẻ bền vững | Buffer tạm để so sánh/KDF, xóa ngay sau sử dụng |
| IV và ciphertext/tag | Bản ghi trên thẻ | IV mới cho mỗi lần mã hóa |

KDF V3 dùng HMAC-SHA256 counter mode với nhãn/context và độ dài mã hóa rõ ràng. Kế hoạch 0.4 giữ nguyên lịch khóa, tiếp tục dùng test vector của V3:

~~~text
F(K, Label, Context, L) =
    lấy L bit đầu của HMAC-SHA256(
        K, counter32(1) || UTF8(Label) || 00 || Context || length32(L)
    )

K_root     = F(S_card, "TTM3/ROOT", cardId16 || keyScheduleVersion || S_backend32, 256)

K_pin_enc  = F(K_root, "TTM3/PIN/ENC", cardId16, 128)
K_pin_mac  = F(K_root, "TTM3/PIN/MAC", cardId16, 256)

K_data_enc = F(K_root, "TTM3/DATA/ENC", cardId16 || cryptoVersion32 || PIN6, 128)
K_data_mac = F(K_root, "TTM3/DATA/MAC", cardId16 || cryptoVersion32 || PIN6, 256)
~~~

- Khóa PIN không phụ thuộc PIN để applet có thể mở PIN record trước khi đối chiếu và để admin hỗ trợ đổi/reset.
- Khóa hồ sơ phụ thuộc PIN, nên đổi/reset PIN phải mở hồ sơ cũ, tạo khóa mới và tái mã hóa.
- Hai bí mật, EC keypair, ID thẻ và số dư không đổi khi sửa hồ sơ hoặc đổi PIN.
- Các khóa theo mục đích khác nhau; không dùng chung khóa AES với MAC.
- Không còn K_pin_check/OwnerPIN verifier trong thiết kế V3 vì bộ đếm và chính sách khóa chuyển về backend. PIN record mã hóa là nguồn PIN trên thẻ.
- Backend không dẫn xuất khóa AES; test vector chỉ dùng dữ liệu fixture để kiểm chứng hợp đồng, không tạo đường lấy khóa thật.

**4. AES-128-CBC, bản ghi PIN và hồ sơ**

| Bản ghi | Plaintext | Khóa |
|---|---|---|
| PIN record | 6 chữ số ASCII | K_pin_enc, xác thực bằng K_pin_mac |
| Profile record | Hồ sơ nhị phân và ảnh | K_data_enc, xác thực bằng K_data_mac |

- Mỗi bản ghi có IV ngẫu nhiên 16 byte riêng, ciphertext, HMAC-SHA256, độ dài, ID thẻ, scheme và phiên bản.
- Padding theo định dạng 80 rồi 00 đã có; kiểm tra tag trước khi giải mã và xử lý padding.
- HMAC bao phủ loại bản ghi, ID, scheme/phiên bản, độ dài, IV và ciphertext để tránh sửa/tráo bản ghi.
- Hai slot cho phép chuẩn bị dữ liệu mới mà giữ nguyên slot active; PIN record và hồ sơ mới chuyển active cùng transaction applet.
- Giữ old slot đến khi hoàn tất ACK. Không xóa dữ liệu cũ ngay khi mới nhận đủ khối.
- Snapshot ciphertext nghiệp vụ có thể giữ bản mã hồ sơ do thẻ tạo để đối chiếu; không xuất PIN record/verifier/bí mật/khóa hoặc raw memory qua API nghiệp vụ.
- Storage scheme mới cho KDF hai bí mật/PIN mã hóa; không nhận nhầm ciphertext theo cơ chế cũ.

**5. Chính sách phiên và RAM theo từng quầy**

5.1. Quầy quản lý:

- Kết nối → ECC → backend kiểm tra ACTIVE → nạp S_backend → nhập PIN → applet so sánh → backend chốt counter → mở phiên sinh viên.
- Giữ K_root và các khóa đã dẫn xuất trong RAM applet đến khi rút thẻ. Xóa S_backend khỏi buffer sau khi có K_root; không dẫn xuất lại giữa các khối đọc.
- Không timeout PIN sau 5 phút. Trong phiên có thể xem thông tin/số dư, nạp nhiều lần và đổi PIN mà không nhập lại PIN hiện tại.
- Khi đổi PIN thành công, cập nhật khóa RAM theo PIN mới; xóa plaintext PIN cũ/mới, không tự đăng xuất chỉ vì đổi PIN.
- Chuyển màn/tab không hủy phiên; quyền admin được quản lý riêng. Mỗi nghiệp vụ vẫn kiểm tra lifecycle và ownership.

5.2. Quầy thanh toán:

- Không dùng phiên quản lý hoặc bằng chứng của giao dịch trước. Hóa đơn mới cần kết nối/insert mới tường minh và ECC + PIN.
- Giữ khóa trong RAM trong lúc xử lý một attempt; kết thúc/hủy attempt thì thu hồi quyền và khóa của nó.
- PIN_WRONG hoặc không đủ số dư: hủy hóa đơn, không mở lại PIN trên hóa đơn đó. Giao dịch tiếp theo dùng invoiceId/QR/attempt mới.
- Hủy hóa đơn không xóa counter PIN sai. Năm lần sai có thể xảy ra trên năm hóa đơn khác nhau.
- Không tự thanh toán hóa đơn sau bằng thẻ đang cắm. Recovery hoàn bù của debit cũ có thể chạy bằng ECC mà không hỏi PIN mới, chỉ trong phạm vi debit đã được cấp phép đó.
- Giữ metadata tài chính bền vững để phục hồi không có nghĩa giữ phiên PIN/khóa AES bền vững.

5.3. Kết thúc/ngắt:

- Rút/đổi thẻ, deselect/reset, đổi chủ đầu đọc, mất tiến trình hoặc lỗi ngữ cảnh: xóa quyền/RAM và dữ liệu UI riêng.
- Backend block thẻ thì hủy quyền sử dụng mới ngay. Recovery giao dịch cũ vẫn theo quyết định bền vững.
- Không serialize S_backend, key objects/engines, PIN verified hoặc roles. Snapshot chỉ chứa allowlist dữ liệu bền vững; 0.4 bổ sung debit hold/marker phục hồi, không thêm khóa.
- Phiên quản lý sau restart phải ECC/PIN lại.
- Phiên ứng dụng quầy thanh toán được theo dõi riêng bằng stationId ổn định, session mới và epoch; close/reopen hủy hóa đơn cũ theo mục 8. Lease quầy không thay TTL PIN quản lý.

**6. Bộ đếm PIN và trạng thái khóa tại backend**

6.1. Vai trò:
- Applet kiểm tra tag, giải mã PIN record, so sánh đủ 6 byte với PIN đầu vào và trả PIN_OK/PIN_WRONG.
- Applet giữ cờ pinVerified/quyền tạm theo phiên; không còn OwnerPIN, bộ đếm PIN bền vững hoặc trạng thái PIN_BLOCKED cục bộ.
- Backend là nguồn chính sách số lần sai, dùng chung cho cả hai quầy và còn nguyên qua restart.
- Không dùng riêng biến Java trong RAM để đếm. pin_failures được lưu trong CSDL, cập nhật nguyên tử và kiểm soát đồng thời.

6.2. Dữ liệu:
- cards.pin_failures: số lần sai liên tiếp, giới hạn 0..5.
- cards.lifecycle_status: ACTIVE hoặc BLOCKED; block_reason: PIN_FAILURES hoặc ADMIN; blocked_at.
- pin_attempts: attemptId duy nhất, cardId, quầy/phiên, loại kiểm tra, kết quả/trạng thái và việc đã áp dụng bộ đếm. Không PIN, S_backend, khóa hoặc plaintext giải mã.
- Mỗi kết quả chỉ áp dụng bộ đếm một lần; retry cùng attemptId không được cộng lặp hay reset sai.

6.3. Quy tắc:
1. Backend kiểm tra lifecycle trước khi gửi lệnh PIN. Thẻ đã BLOCKED không được tiếp tục thử PIN ở luồng sinh viên.
2. ECC sai, cardId lạ, S_backend sai, HMAC/PIN record hỏng, PIN sai định dạng hoặc mất kết nối không được tự quy thành PIN_WRONG.
3. PIN_WRONG đã nhận hợp lệ: transaction DB khóa card row, tăng pin_failures đúng một lần; đạt 5 thì chuyển BLOCKED/PIN_FAILURES trong cùng transaction.
4. PIN_OK đã nhận hợp lệ: nếu thẻ vẫn ACTIVE và kết nối đúng thì reset pin_failures=0; chỉ sau khi chốt DB mới cấp quyền sử dụng ở backend.
5. Nếu admin khóa thẻ đồng thời, PIN đúng không tự mở khóa. Xử lý kết quả và block dưới cùng ràng buộc khóa/phiên bản.
6. Mất phản hồi PIN: không cấp quyền và không đoán đúng/sai để sửa bộ đếm. Lưu trạng thái không xác định của attempt nếu cần; thử mới phải có attemptId mới hoặc truy hồi kết quả đã có một cách idempotent.
7. Kết quả PIN/bộ đếm là transaction xác thực riêng: rollback giao dịch tiền/hồ sơ không được hoàn lại số lần nhập sai đã xác nhận.
8. Đúng PIN trước khi bị khóa reset chuỗi sai; thẻ đã khóa chỉ admin khôi phục mới mở lại.
9. Admin đổi/reset PIN thành công: reset pin_failures=0 cùng DB_COMMITTED đúng một lần. Hủy trước commit giữ bộ đếm cũ; replay operation không reset các lần sai phát sinh sau đó.
10. Ở quầy thanh toán, PIN_WRONG đã xác nhận hủy hóa đơn và yêu cầu tạo hóa đơn mới. Mất phản hồi PIN không cấp quyền và đi theo hủy/đối soát của attempt, không tự thử lại trên hóa đơn đó.

Cả hai quầy hiển thị đúng thông báo khi sinh viên dùng thẻ bị khóa:
“Thẻ hiện đang bị tạm khoá! Vui lòng liên hệ với admin”

Luồng admin vẫn được đăng nhập để khôi phục hoặc xóa thẻ đang khóa; không mở một lối sinh viên bỏ qua trạng thái.

**7. ECC và đăng ký ban đầu**

- ECDSA P-256/SHA-256; applet sinh private key, backend đăng ký public key.
- Backend tìm ID thẻ trong DB, dùng public key đã lưu để kiểm tra challenge mới; không thay khóa bằng public key tự gửi khi xác minh sai.
- M = TTM3-AUTH || purpose || cardId || cardSessionId || R32 || operationContext; R dùng một lần và có thời hạn xử lý ngắn.
- ECC kết nối quản lý thành công được gắn đúng cardId/fingerprint/session/terminal; không ký lại từng khối hồ sơ khi phiên chưa đổi.
- Thanh toán dùng bằng chứng ECC mới cho ngữ cảnh hóa đơn/giao dịch. Đây là xác thực thẻ cho giao dịch, không phải signed grant cho từng lệnh.
- Thẻ trắng chưa có ID đăng ký: admin hợp lệ, đọc EC public key, proof of possession, reserve cardId/S_backend/hồ sơ pending rồi INIT.
- Không cho tự đăng ký một thẻ đã khởi tạo nhưng không có trong DB. Sau xóa cứng, thẻ cũ bị coi là không đăng ký, không tự nhận lại ID/share cũ.
- S_backend được gửi nội bộ backend, không có trong DTO Swing/list/detail/log. Private key ECC và S_card không được xuất qua APDU.

**8. Quầy thanh toán: hai tab, QR 5 phút và phiên ứng dụng**

8.1. Hai tab:

- Tab nhân viên chọn món/số lượng, tạo hóa đơn và QR. Backend chốt giá, tổng tiền và expires_at.
- Tab khách: màn chờ → QR/tổng tiền/đồng hồ → insert mới → ECC → PIN → đang xử lý → kết quả.
- Hai tab dùng cùng invoiceId/state. Mỗi cặp invoiceId/connectionId chỉ có một ngữ cảnh/hộp PIN, polling không mở lặp.
- Không sửa nội dung/tổng tiền khi QR đang dùng; không kéo dài hạn bằng cách vẽ lại QR cũ.
- Đây là phân chia nhiệm vụ UI, không thêm hệ thống tài khoản bán hàng hoặc luồng điện thoại chưa được yêu cầu.
- QR thanh toán là QR hóa đơn trả bằng số dư thẻ. QR chuyển khoản nạp ngân hàng ở quản lý là luồng riêng.

8.2. Thời hạn và một lần thử:

- Backend đặt expires_at = issued_at + 5 phút và cung cấp thời gian server cho UI.
- Kiểm tra hạn ở bắt đầu xác thực, trước PREPARE và trước quyết định commit; Swing timer chỉ hiển thị.
- Hết hạn khi còn chờ/chưa commit: hủy, tab khách về màn chờ và tab nhân viên yêu cầu tạo mới.
- Mỗi invoice tối đa một payment_attempt. PIN sai, không đủ số dư, ECC sai/không đúng thẻ, hủy hộp PIN hoặc lỗi xác thực đã kết luận đều hủy hóa đơn.
- Form chặn sai định dạng trước khi gửi attempt hoặc chưa đưa thẻ vào vẫn là chờ thao tác. Request gửi lặp chỉ truy hồi kết quả attempt cũ.
- Protocol commit hợp lệ trước deadline có thể hoàn tất sau mốc 5 phút nếu không có lỗi/yêu cầu hủy và session còn hợp lệ; không mở attempt mới từ QR đã hết hạn.
- Nếu lỗi/đóng quầy/mất lease trước SUCCESS: abort hoặc reversal theo mục 10. Không dùng COMMIT_DECIDED đơn lẻ làm kết quả bán hàng.
- Hóa đơn PAID đã chốt vẫn PAID nếu UI mất phản hồi. UI chỉ báo thành công/hủy xong theo trạng thái cuối backend, không đoán từ timeout.

8.3. Đóng/mở và tắt cưỡng bức:

- stationId ổn định theo từng quầy; stationSessionId mới mỗi lần mở; backend tăng epoch để từ chối request/heartbeat của phiên cũ. ID này khác connectionId của thẻ.
- Open-session đóng phiên cũ của đúng quầy, hủy hóa đơn OPEN; thao tác tiền đang dở chuyển hủy/đối soát. Không hủy quầy khác.
- UI mở lại vào giỏ trống. Lịch sử hóa đơn cũ chỉ đọc, không có nút tiếp tục thanh toán hoặc dùng lại QR.
- Close bình thường gọi backend lưu CLOSED/cancel trước, thu hồi quyền mới ngay; không chờ reader lock mới ghi được quyết định.
- Đề xuất heartbeat 5 giây, lease 15 giây để phát hiện kill/mất mạng; chạy worker riêng, không phụ thuộc hộp thoại PIN/EDT. Bản hiện tại dùng cố định 5/15 giây trong ApiClient/StationSessions, không phát hiện crash tức thì.
- Backend restart đóng/dọn session cũ trước khi nhận thanh toán mới. Close/lease không hủy ngược một hóa đơn đã PAID.
- Chưa đủ điều kiện hoàn bù thì hiển thị CANCEL_PENDING và yêu cầu kết nối lại đúng thẻ. Chặn hóa đơn/giao dịch mới trên thẻ còn hold.

**9. Nạp tiền và đổi PIN tại quầy quản lý**

9.1. Nạp:

- Cần phiên quản lý sinh viên đúng thẻ, đã ECC/PIN và chưa bị khóa; không hỏi lại PIN khi tạo QR hoặc apply tiền trong cùng phiên hợp lệ.
- Giữ xác nhận ngân hàng/webhook, số tiền/nội dung, chống nhận trùng và đối soát. QR hoặc thông báo phía UI không chứng minh đã nhận tiền.
- Worker được apply CREDIT khi đúng thẻ và phiên quản lý còn hợp lệ. Nếu tiền đến khi đã rút thẻ/mất phiên, vẫn ghi nhận BANK_RECEIVED và chờ xác thực lại.
- Nạp nội bộ hủy trước commit thì số dư/ledger không đổi; tiền đã nhận ở ngân hàng vẫn phải được giữ làm khoản chờ.
- Sau protocol COMMIT_DECIDED, CREDIT tiếp tục quy tắc hoàn tất/đối soát V3; không tự xóa khoản nhận hoặc phát sinh hoàn ngân hàng.
- Khi xóa thẻ, khoản nạp chưa đồng bộ chuyển sang kho đối chiếu độc lập theo mục 11. Callback đến muộn phải tìm được reference cũ, không nạp cho thẻ mới cùng MSSV.

9.2. Đổi PIN:

- Sinh viên trong phiên hợp lệ nhập PIN mới và xác nhận, không hỏi lại PIN cũ.
- Applet mở hồ sơ cũ, tạo PIN record mới bằng khóa hai bí mật, tạo khóa hồ sơ từ hai bí mật + PIN mới và tái mã hóa ở slot chờ.
- Admin reset PIN không cần biết PIN hiện tại nhưng phải đăng nhập admin, kết nối đúng thẻ và ECC.
- Khi chốt PIN mới tại DB_COMMITTED, reset pin_failures=0 trong cùng transaction và ghi marker/phase đã áp dụng. Recovery/replay cùng operation không reset lần nữa.
- Ví dụ counter 4 → admin reset thành công → 0; sau đó người dùng sai một lần → 1; replay reset cũ vẫn phải giữ 1.
- Hủy trước COMMIT_DECIDED giữ PIN/hồ sơ/counter cũ. Sau quyết định commit hoàn tất/đối soát đúng operation.
- Không tự đổi BLOCKED thành ACTIVE; admin cần thao tác khôi phục riêng. Giữ lịch sử attempt, hai bí mật/EC/ID/số dư không đổi.

**10. Tính nguyên tử giữa CSDL và thẻ**

10.1. Transaction cục bộ và giao thức V3:

- SQL ROLLBACK chỉ hoàn tác transaction PostgreSQL; JCSystem transaction chỉ nguyên tử bộ nhớ persistent của applet. Không có một transaction tự động phủ cả DB và thẻ bị rút.
- Không giữ SQL transaction/row lock trong 5 phút chờ QR/PIN. Tạo invoice + lines + tổng tiền bằng transaction ngắn, sau đó điều phối bằng journal.
- Giao thức dữ liệu vẫn là CREATED → CARD_PREPARED → COMMIT_DECIDED → CARD_COMMITTED → DB_COMMITTED → ACK/SYNCED.
- Trước COMMIT_DECIDED, ABORT candidate và giữ active cũ. Sau quyết định này, hoàn tất/đối soát đúng operation, không đảo riêng mirror hoặc suy ra thất bại từ lỗi mạng.
- INIT/UPDATE/PIN/CREDIT giữ quy tắc đó. DEBIT thanh toán bổ sung quyết định nghiệp vụ và reversal ở dưới; card operation SYNCED không tự đồng nghĩa hóa đơn PAID.

10.2. Kết quả nghiệp vụ của hóa đơn:

| Trạng thái | Ý nghĩa |
|---|---|
| OPEN | QR còn hạn, chưa có attempt |
| PAYMENT_PENDING | Đang xử lý attempt, chưa chốt bán hàng |
| CANCEL_PENDING | Đã yêu cầu hủy; đang abort/xác định debit/hoàn bù; không cho trả lại hóa đơn này |
| PAID | Hai phía đã xác nhận debit, SUCCESS bền vững |
| CANCELLED | Chưa trừ hoặc đã hoàn bù đủ; số dư hai phía về trước thanh toán |

- SUCCESS/CANCEL là quyết định một lần của invoice/attempt, tách COMMIT_DECIDED của protocol.
- Sau ACK debit, applet vẫn giữ metadata debit đủ để reversal cho tới quyết định nghiệp vụ cuối. Không xóa hết bằng chứng debit ở ACK như hiện tại.
- SUCCESS chỉ chốt sau kiểm tra hai phía, session/epoch và cancel flag dưới thứ tự khóa thống nhất. Sau PAID, FINALIZE/RELEASE dọn hold idempotent, không trừ thêm.
- CANCEL trước debit: abort, xác nhận active cũ rồi CANCELLED.
- CANCEL sau khi đã quyết định/áp dụng debit nhưng chưa SUCCESS: hoàn tất xác định debit gốc nếu cần, rồi thực hiện REVERSAL liên kết debit đó; xác nhận số dư cả hai bên trở về B0 mới CANCELLED.
- Thẻ bị rút/không rõ trạng thái: CANCEL_PENDING, chờ đúng thẻ trở lại; không tuyên bố đã hoàn tức thì.
- PAID đã chốt trước close/hủy/lỗi hiển thị vẫn PAID. Hoàn một giao dịch đã bán thành công là nghiệp vụ khác, không nằm trong đợt này.

10.3. Hoàn bù và đồng thời:

- REVERSAL có ID riêng, parent debit duy nhất, amount lấy từ debit gốc. Applet kiểm tra ID/thẻ/version/số tiền/trạng thái, cập nhật số dư + marker trong transaction cục bộ.
- Backend dùng transaction ngắn cập nhật mirror/ledger/phase, constraint ngăn đảo cùng debit hai lần. Không dùng CREDIT tùy ý hoặc xóa debit khỏi ledger.
- Counter/version tiếp tục tăng; chỉ số dư và tác động bán hàng ròng quay về ban đầu. PIN sai đã xác nhận không được rollback.
- Ví dụ B0=100.000đ, debit 35.000đ, hủy trước SUCCESS: hoàn bù 35.000đ → card và DB cùng 100.000đ → CANCELLED; ledger giữ debit và reversal.
- Chặn nghiệp vụ mới khi financial hold/cancel pending. ECC và đúng thẻ đủ cho recovery khoản trừ cũ, không mở quyền nạp tiền tùy ý không PIN.
- Close/lease lưu quyết định DB ngay, không chờ reader monitor. SUCCESS và CANCEL tranh chấp bằng lock/CAS; chỉ một quyết định thắng.
- Không giữ SQL lock qua APDU. Quy tắc khóa dự kiến session → card → invoice → attempt/operation, dùng thống nhất trong các đường chạy.
- UI guard session/epoch/invoice/attempt/request sequence để response cũ không ghi đè kết quả cuối.

10.4. Khóa thẻ trong thao tác:

- Chặn nghiệp vụ mới ngay, không dùng reset/mở khóa để ghi đè số dư.
- Thao tác chưa có protocol commit thì abort khi chứng minh chưa áp dụng.
- Operation đã có quyết định commit vẫn cần hoàn tất xác định giao thức; invoice có CANCEL thì tiếp tục reversal, không bị repair nhầm thành PAID.
- Manual block không cản đối soát/hoàn bù của giao dịch cũ; sau đó lifecycle vẫn BLOCKED.
- Không xóa operation chưa rõ kết quả. Chi tiết các mốc lỗi và kết quả mong đợi nằm ở kế hoạch đồng bộ 0.4.

**11. Khôi phục và xóa cứng**

11.1. Khôi phục:

- Chỉ admin mở lại thẻ BLOCKED, không cấp lại thẻ mất/hỏng.
- Đăng nhập admin, kết nối đúng thẻ/ECC và đối soát thao tác chờ trước.
- DB đặt ACTIVE, pin_failures=0, xóa lý do/thời điểm khóa; giữ ID/keypair/hai bí mật/PIN/hồ sơ/số dư.
- Không phục hồi phiên sinh viên cũ. Thẻ đã xóa cứng không khôi phục bằng backend hoặc kho đối chiếu tài chính.

11.2. Kiểm tra và xác nhận theo đúng thứ tự:

1. Admin hợp lệ, thẻ BLOCKED, đúng thẻ đang kết nối/ECC.
2. Đối soát đủ để biết số dư thực, không bỏ qua debit/reversal chưa rõ bằng một nút xác nhận.
3. Đọc balance thực và counter/walletVersion trên thẻ, đối chiếu DB. Nếu > 0: “Thẻ vẫn còn số dư! Xác nhận xoá?”
4. Sau bước số dư, đếm N khoản nạp/giao dịch ngân hàng chưa xử lý xong. Nếu N > 0: “Thẻ vẫn còn [số giao dịch] chưa đồng bộ! Tiếp tục xoá?” Ví dụ: “Thẻ vẫn còn 3 giao dịch chưa đồng bộ! Tiếp tục xoá?”
5. Hai cảnh báo có hai xác nhận riêng; hủy một bước thì không xóa. Hủy không có nghĩa xóa khoản tiền ngân hàng.
6. Preview gắn card/connection/counter/walletVersion và revision của tập khoản chờ. Cùng N nhưng khác item/amount/status vẫn cần preview/xác nhận lại.
7. Execute khóa/recheck trạng thái và khoản chờ; stale thì từ chối, không dùng xác nhận cũ.

N tính theo khoản gốc, không đếm APDU/retry/webhook lặp. Gồm chờ ngân hàng, đã nhận chưa CREDIT và khoản REVIEW/EXPIRED còn tiền chưa xử lý; chỉ coi đã đồng bộ khi xác định đã ghi đủ, không còn khoản nhận dư/chưa đối chiếu. Số tiền hoàn phải tách received/credited/refundable, không suy từ số lượng N.

11.3. Xóa đăng ký và lưu đối chiếu trong cùng SQL transaction:

- Tạo hồ sơ đối chiếu độc lập, sao chép các khoản chưa xong và bằng chứng tối thiểu trước khi xóa FK. Giữ liên kết bank reference/provider ID để xử lý callback muộn.
- Kho đối chiếu có original card ID, MSSV/tên tối thiểu, thời điểm/admin xóa và tiền/chứng từ/trạng thái cần hoàn; không có FK buộc giữ card row.
- Không lưu PIN, S_backend, S_card, private key, raw snapshot, ảnh hoặc nguyên hồ sơ vào kho tài chính.
- Xóa card row/đăng ký, hồ sơ/ảnh/pending, S_backend, ví mirror, cipher snapshot, attempts và dữ liệu riêng khác không cần cho khoản chờ. Dữ liệu tài chính cần đối chiếu được giữ độc lập.
- Giữ bank_events/dedup toàn cục, gỡ FK active card/topup sau khi chuyển liên kết; không delete rồi recreate làm mất chống trùng.
- Archive và xóa phải cùng transaction: lỗi thì rollback cả hai. Không ảnh hưởng admin, menu, cấu hình hay thẻ khác.
- Không giữ soft delete/undo hoặc đủ vật liệu để khôi phục thẻ. Đăng ký/list/tìm thẻ không lấy kho tài chính làm nguồn thẻ đang hoạt động.
- Đồng ý bỏ số dư trên thẻ không tự hoàn số dư đó. Ví dụ card còn 50.000đ, nạp chờ 30.000đ chưa ghi: khoản cần đối chiếu nạp là 30.000đ, không tự thành 80.000đ.
- Callback đến muộn tra active topup hoặc reference đã lưu, ghi khoản nhận vào hồ sơ hoàn tiền; không tái tạo thẻ, không nạp cho thẻ mới cùng MSSV.
- Chưa nhận tiền thì chỉ WAITING_BANK; có bằng chứng tiền đến mới chuyển trạng thái phù hợp. Giữ reference để nhận diện khoản tới muộn cả sau khi đã đóng hồ sơ chờ.
- Đợt này cần lưu và tra cứu đối chiếu, chưa xây chuyển tiền hoàn ngân hàng tự động. Không dùng tài khoản nhận tiền của đơn vị làm tài khoản người gửi.

11.4. RAM và file mô phỏng:

- Thu hồi phiên/khóa RAM trước khi mất ánh xạ bí mật, gỡ inventory và dọn đúng file .card/bản sao phục hồi thuộc thẻ.
- SQL và filesystem là hai tài nguyên khác nhau. File lỗi dọn phải báo riêng, không khôi phục đăng ký/secret đã xóa.
- Chỉ báo xóa toàn bộ khi các bước cần thiết đã xác nhận. Kiểm tra đường dẫn tuyệt đối trước dọn, không xóa ngoài phạm vi project.
- Xóa cứng là không còn khôi phục bằng backend ứng dụng, không phải cam kết xóa forensic mọi bản sao ngoài ứng dụng.

**12. CSDL và hợp đồng API**

Nền V3 hiện có được giữ; schema mở rộng đã có trong V4; chưa áp dụng migration lên DB ứng dụng.

| Nhóm | Trách nhiệm / phần bổ sung 0.4 |
|---|---|
| cards/card_secrets | Hồ sơ bản rõ, EC public key, lifecycle/pin_failures; S_backend riêng; không PIN/S_card/derived keys |
| pin_attempts | Kết quả xác thực và áp dụng counter đúng một lần |
| card_operations | Journal/phase protocol; marker reset counter khi admin PIN; liên kết debit/reversal |
| card_wallets/wallet_operations | Balance/version/idempotence; REVERSAL có parent debit duy nhất |
| station_sessions | stationId ổn định, session/epoch, heartbeat/lease, trạng thái đóng |
| invoices/payment_attempts | Một attempt/invoice; outcome SUCCESS/CANCEL riêng protocol; CANCEL_PENDING; B0/debit/reversal/owner session |
| refund_cases/refund_items | Chứng từ tối thiểu độc lập card đã xóa, nhận/ghi/hoàn bao nhiêu và trạng thái xử lý |
| bank_events | Giữ dedup/reference toàn cục; liên kết archive sau khi active card/topup bị xóa |
| card state/snapshot | Allowlist debit hold và marker reversal/finalize; nâng version/capability, không lưu quyền RAM |
| card_cipher_snapshots | Chỉ bản mã hồ sơ phục vụ đối chiếu, không PIN record/raw keys/signed attestation |
| Auth/ngân hàng | Giữ password hash/token/OTP/HMAC webhook và quyền API |

- API quản lý giữ phiên không TTL 5 phút; đổi PIN sinh viên chỉ nhận PIN mới trong phiên hợp lệ.
- Thanh toán cần session/epoch/invoice/attempt và idempotency; backend quyết định giá/hạn/quyền, không chấp nhận attempt mới cho hóa đơn đã thất bại.
- Thêm open/heartbeat/close session quầy, invoice status/cancel, delete preview với revision và hai confirm, tra cứu hồ sơ đối chiếu dành cho admin.
- Reversal/release do coordinator điều khiển theo debit gốc; không expose một API cộng số tiền tùy ý.
- DTO nghiệp vụ không trả S_backend, PIN, private keys hoặc raw state. ReaderStatus lấy counter từ DB và connectionId từ lần cắm thẻ.
- Query tìm/lọc/sắp xếp/phân trang vẫn dùng hồ sơ bản rõ và truy vấn tham số hóa.
- Chi tiết ràng buộc, thứ tự khóa và nâng cấp trước V3 nằm ở mục 7 của kế hoạch đồng bộ. Tên bảng mới đã chốt trong V4__invoice_outcome_and_retention.sql.

**13. APDU V3 và phần mở rộng cần làm**

| Lệnh/nhóm chức năng | Trách nhiệm |
|---|---|
| INFO/EC public key/PROVE_CARD | Metadata, ECC và capability; không issuer/RSA |
| Context/LOAD_SERVER_SECRET/VERIFY_PIN | RAM theo quyền backend; so sánh PIN, không đếm sai trên applet |
| Read/input/chunk/PREPARE | Đọc/chuẩn bị dữ liệu, role/version/operation ID; active cũ chưa đổi |
| COMMIT/ACK/ABORT/GET_STATE | Transaction cục bộ/idempotence/recovery; 0.4 ACK debit giữ metadata đến quyết định nghiệp vụ |
| REVERSAL (mới) | Đảo đúng debit chưa SUCCESS, amount/id/version có ràng buộc |
| FINALIZE/RELEASE (mới) | Bỏ hold sau kết quả cuối, không cộng/trừ thêm |
| EXPORT_PROFILE_CIPHER | Chỉ bản mã hồ sơ, không PIN/bí mật |

REVERSAL/FINALIZE là nhóm chức năng dự kiến; opcode và DTO cụ thể chốt đồng thời ở Protocol/CardClient/applet. Cần tăng capability/state format và cập nhật SnapshotRuntime; KDF/storage scheme mật mã vẫn V3. Applet cũ thiếu capability không được chạy luồng mới hoặc fallback sang CREDIT không ràng buộc.

Đã bỏ theo V3 và không đưa lại:

- RSA, RSA-OAEP, issuer/provision, signed grant, ký từng lệnh/state/biên nhận và mã hóa đường truyền.
- OwnerPIN/verifier/bộ đếm applet; TTL 5 phút của PIN quản lý; xóa khóa sau từng khối; PIN mới cho từng lần nạp.
- Soft delete/undo và phục hồi đăng ký thẻ đã xóa; công cụ chuyển khóa/ciphertext V2.
- AES-GCM backend cho hồ sơ/tên hóa đơn/pending/file mô phỏng mới.

Giữ AES-CBC/HMAC trên thẻ, RNG an toàn, ECC, hai slot, transaction cục bộ, journal/version/idempotence và đối soát; giữ PBKDF2 admin, token/OTP/webhook HMAC và quyền API. Chia khối APDU vẫn do giới hạn dung lượng. Chứng từ nạp độc lập được giữ theo 0.4, không xem đó là soft delete thẻ.

**14. Migration, dữ liệu cũ và bộ demo**

- V3__ecc_demo_reset.sql hiện xóa dữ liệu thẻ/nạp cũ trước khi đổi schema. Thêm V4 sau V3 không giữ được tiền/chứng từ đã bị xóa trước đó.
- Khi DB ở V1/V2, cần preflight giữ khoản tài chính và bằng chứng tối thiểu vào kho độc lập trước V3; commit/kiểm đếm xong mới cho V3 chạy, rồi V4 bổ sung schema.
- Preflight có marker idempotent và chặn startup nếu không xác minh đủ. Gỡ/giữ FK bank_events đúng thứ tự để V3 không xóa chứng từ cần đối chiếu.
- Khi DB đã V3, chỉ bổ sung V4 và nâng capability/snapshot phù hợp; không sửa checksum migration đã chạy, không xóa thẻ V3 do người dùng tạo để vượt lỗi format.
- Trước thay đổi, dừng backend/worker/quầy cũ và kiểm tra đúng DB/Flyway version. Dữ liệu test ở ttm_test tách riêng; không suy mọi khoản của DB ứng dụng là tiền giả.
- Chỉ dọn bộ V2/file/bản sao/issuer sau khi chứng từ cần giữ được xác nhận. Không lấy nguyên dump chứa hồ sơ/keys làm kho hoàn tiền.
- Giữ admin, menu, cấu hình, danh mục chung và thẻ khác đúng phạm vi. Không reset/reuse reference ngân hàng cũ.
- Không xây chuyển PIN/keys/ciphertext V2 sang V3; khoản CREDIT legacy chưa rõ đã ghi bao nhiêu phải REVIEW, không tự kết luận hoàn toàn bộ.
- Nếu chưa có demo V3 như hiện trạng đã ghi, tạo demo theo capability mới sau migration/kiểm thử. Mỗi thẻ có ID/keypair/shares riêng.
- Lượt cập nhật kế hoạch này không chạy migration, seed hoặc dọn/xóa dữ liệu.

**15. Đợt triển khai tiếp theo và kiểm chứng**

| Đợt | Công việc | Điều kiện đạt |
|---|---|---|
| 1 | Schema bổ sung + preflight trước V3, contracts và dữ liệu đối chiếu | Không mất khoản nhận/chờ; DB V3 nâng thêm không mất dữ liệu; không đổi checksum cũ |
| 2 | Admin reset counter đúng một lần; delete hai cảnh báo + archive + callback muộn | Đúng thứ tự balance rồi pending; xóa/giữ tài chính nguyên tử; PIN replay không reset lỗi mới |
| 3 | Session quầy/epoch/heartbeat, một attempt, hủy hóa đơn khi thất bại | Reopen không dùng đơn cũ; PIN sai/FUNDS hủy; quầy khác không ảnh hưởng |
| 4 | Applet hold/REVERSAL/RELEASE, coordinator và recovery | CANCELLED chỉ khi net debit bằng 0; PAID không bị đảo vì mất phản hồi; reversal đúng một lần |
| 5 | Kiểm thử tích hợp, demo và tài liệu kết quả | Ma trận lỗi đạt trên jCardSim/PostgreSQL thật; UI phản ánh kết quả thật |

Đợt 3 chưa đủ để tuyên bố nguyên tử sau debit nếu chưa có đợt 4. Không bật luồng mới nửa chừng với applet cũ thiếu capability.

Giữ kết quả kiểm chứng mật mã V3 và chạy lại khi có thay đổi liên quan; bổ sung các nhóm 0.4:

- Xóa: balance/pending bằng 0 hoặc dương, hủy từng cảnh báo, pending thay đổi dù count không đổi, webhook đồng thời/delete rollback, callback muộn và dedup, không ghép thẻ mới cùng MSSV.
- Tài chính lưu lại: chưa nhận/đã nhận/đã ghi/nhận dư/REVIEW, số tiền thực cần đối chiếu; không lưu secrets hoặc nguyên hồ sơ, không tự chuyển tiền hoàn.
- PIN admin: 4 → reset 0; abort giữ 4; sai mới 1 và replay reset vẫn 1; manual block không bị tự mở.
- Session: close/reopen/kill/lease/backend restart, heartbeat cũ, quầy khác, không resume hóa đơn cũ.
- Invoice: sai PIN/FUNDS/ECC/hủy PIN phải tạo mới; 5 sai qua nhiều hóa đơn khóa; hạn 299/300/301 giây; không thay giá/QR/attempt.
- Fault injection: trước/sau PREPARE, protocol COMMIT, card debit, DB mirror, ACK, SUCCESS, reversal và RELEASE; rút thẻ rồi cắm lại đúng/sai thẻ.
- Đồng thời: close/lease/admin block so với SUCCESS/CANCEL; operation debit SYNCED không repair hóa đơn đã CANCEL thành PAID.
- Idempotence: gửi lặp debit/reversal/ACK/release không cộng/trừ hai lần; version tăng, balance trở B0, PIN counter không quay lùi.
- Migration: khoản chờ được giữ trước V3; V4 giữ dữ liệu V3; chỉ dọn đúng phạm vi, admin/menu/config còn nguyên.
- UI: hai tab cùng outcome, một hộp PIN/context, thông báo CANCEL_PENDING trung thực và response cũ không ghi đè trạng thái mới.

Ma trận chi tiết và các mốc chốt quyết định ở [kế hoạch đồng bộ 0.4](KE-HOACH-DONG-BO-THE-BACKEND.md). jCardSim là mốc nghiệm thu applet; các ràng buộc DB cần PostgreSQL thật. Không dùng 38 test cũ để đánh dấu các ca 0.4 đã đạt.

**16. Hiện trạng và giới hạn bằng chứng**

Nguồn hiện tại là Java Swing/FlatLaf, tiếng Việt; nền V3 đã có hai tab thu ngân và luồng ECC/PIN/QR. Bản kế hoạch không đánh dấu các phần UI cũ là chưa được viết chỉ vì còn thiếu yêu cầu 0.4.

| Phạm vi | Bằng chứng / việc còn lại |
|---|---|
| V3 mật mã/giao thức/UI | 44 ca không phụ thuộc PostgreSQL đã đạt, gồm applet/protocol/crypto/contract/render UI/reversal; chi tiết ở KIEM-CHUNG.md |
| PostgreSQL/HTTP/demo thật | Chưa xác nhận chạy trọn vẹn do kết nối localhost của môi trường công cụ |
| Delete khoản chờ | Đã viết archive/refund và hai xác nhận; assertion DB còn bị chặn |
| Admin PIN counter | Đã đặt reset cùng DB_COMMITTED với marker idempotent; assertion DB còn bị chặn |
| Đóng/mở quầy | Đã viết station/session/epoch/lease và đóng/reopen; assertion DB còn bị chặn |
| Hủy/hoàn bù | Applet hold/REVERSAL/RELEASE đã kiểm thử; coordinator DB cần PostgreSQL thật |
| UI mới 0.4 | Render CANCEL_PENDING và phản hồi cũ đã kiểm thử; hai cảnh báo và end to end cần DB thật |

Đã đổi mã nguồn/schema/test và đóng gói theo yêu cầu triển khai. Chưa thực thi migration/seed/xóa trên DB ứng dụng do kết nối bị môi trường chặn. Kết quả kiểm chứng là theo từng nhóm ở KIEM-CHUNG.md, không phải full suite đạt.

**17. Tài liệu và nguồn kỹ thuật**

- [Kế hoạch đồng bộ 0.4](KE-HOACH-DONG-BO-THE-BACKEND.md): nguồn chi tiết cho retention/phiên quầy/outcome/reversal/migration và nghiệm thu; thay các điều khoản mâu thuẫn trong kế hoạch V2.
- [KIEM-CHUNG.md](KIEM-CHUNG.md): chỉ kết quả thực sự đã chạy; phân biệt mã 0.4 đã viết, các nhóm đã kiểm thử và phần SQL/HTTP còn bị chặn.
- Khi triển khai xong từng đợt mới cập nhật README/THIET-KE/hướng dẫn demo theo hành vi đã có. Không sửa AGENTS.md hoặc commit/push/deploy trong bước lập kế hoạch.
- [Oracle Java Card Signature](https://docs.oracle.com/cd/E59935_01/api/javacard/security/Signature.html): ECDSA/HMAC.
- [NIST SP 800-108 Rev.1](https://csrc.nist.gov/pubs/sp/800/108/r1/upd1/final): KDF bằng PRF.
- [NIST SP 800-38A](https://csrc.nist.gov/pubs/sp/800/38/a/final): CBC/IV.
- [Oracle KeyBuilder](https://docs.oracle.com/en/java/javacard/3.1/jc_api_srvc/api_classic/javacard/security/KeyBuilder.html): transient/persistent key data.
- [PostgreSQL ROLLBACK](https://www.postgresql.org/docs/current/sql-rollback.html): phạm vi transaction CSDL.
- [Oracle JCSystem](https://docs.oracle.com/en/java/javacard/3.1/jc_api_srvc/api_classic/javacard/framework/JCSystem.html): transaction persistent applet, RAM không thuộc rollback.
