# Kế hoạch triển khai đồng bộ 0.4 — xóa thẻ có khoản chờ, phiên quầy và hủy thanh toán

Ngày cập nhật: 05/11/2026. Project: C:/JavaCard/TTM_CuoiKy.

Trạng thái: mã nguồn 0.4 đã được triển khai và đóng gói; phân biệt phần đã kiểm thử độc lập với phần SQL/HTTP chưa chạy được. Bản này thay thế nội dung kế hoạch đồng bộ V2 trước đây. Nền mật mã V3 vẫn theo KE-HOACH-ECC-VA-DON-GIAN-BAO-MAT-THE.md; tài liệu này quy định chi tiết phần tài chính và vòng đời hóa đơn được bổ sung.

Bản 0.4 có 44 ca không phụ thuộc PostgreSQL đạt, gồm 5 ca applet reversal và 5 ca Swing. Sáu nhóm SQL/HTTP dừng ở lỗi kết nối getsockopt; chưa áp dụng migration/seed DB ứng dụng. Bằng chứng hiện hành ở KIEM-CHUNG.md; không coi mã đã viết là assertion DB đã đạt.

## 1. Quyết định mới và phần thay thế kế hoạch cũ

| Quyết định | Hợp đồng mới |
|---|---|
| Thứ tự kiểm tra xóa | Kiểm tra số dư thực trên thẻ trước, rồi kiểm tra các khoản nạp chưa đồng bộ |
| Cảnh báo số dư | Nếu > 0: “Thẻ vẫn còn số dư! Xác nhận xoá?” |
| Cảnh báo khoản chờ | Nếu N > 0: “Thẻ vẫn còn [số giao dịch] chưa đồng bộ! Tiếp tục xoá?”; ví dụ hiển thị “Thẻ vẫn còn 3 giao dịch chưa đồng bộ! Tiếp tục xoá?” |
| Sau khi xác nhận xóa | Xóa đăng ký/hồ sơ/bí mật của thẻ, giữ riêng dữ liệu tài chính chưa đồng bộ để đối chiếu và hoàn tiền sau này |
| Admin đổi/reset PIN | Sau khi chốt PIN mới thành công, reset pin_failures=0 đúng một lần; không tự mở trạng thái BLOCKED do admin |
| Đóng quầy | Backend hủy các hóa đơn chưa thành công của đúng phiên quầy; QR cũ không còn được dùng |
| Mở lại quầy | Phiên mới hủy/dọn phiên cũ của cùng quầy, vào đơn hàng trống, phải tạo hóa đơn mới |
| PIN sai/không đủ số dư | Người dùng đã chọn: hủy hóa đơn, phải tạo hóa đơn mới; số lần PIN sai vẫn tích lũy qua các hóa đơn |
| Thanh toán không thành công | Kết quả cuối CANCELLED chỉ khi số dư hai bên giữ nguyên hoặc đã được hoàn bù về mức trước thanh toán |
| Mất phản hồi sau thành công | Nếu PAID đã chốt bền vững thì vẫn là giao dịch thành công; đọc lại kết quả, không hủy một giao dịch đã thành công chỉ vì UI không nhận phản hồi |

Hai thay đổi so với 0.3 cần thể hiện trực tiếp trong triển khai:
- “Xóa cứng toàn bộ dữ liệu thuộc thẻ” có ngoại lệ mới cho hồ sơ tài chính chưa đồng bộ cần hoàn tiền. Ngoại lệ không cho phép khôi phục thẻ.
- Với DEBIT của hóa đơn chưa có quyết định SUCCESS, quyết định COMMIT của thao tác thẻ không tự động biến hóa đơn thành PAID. Nếu hủy đã thắng, hoàn tất phần giao thức cần thiết rồi hoàn bù và chốt CANCELLED. INIT, UPDATE, PIN và CREDIT không tự đổi sang quy tắc hủy hóa đơn này.

## 2. Điểm xuất phát V3 và các thay đổi 0.4 đã viết

| Nguồn | Điểm xuất phát trước 0.4 | Thay đổi 0.4 trong mã nguồn |
|---|---|---|
| [CardService.java](C:/JavaCard/TTM_CuoiKy/backend/src/main/java/vn/edu/smartcard/backend/CardService.java) | Delete chỉ kiểm tra balance/card_operations; xóa cả topups/bank_events; reset PIN chưa reset counter | Preview hai nhóm cảnh báo; lưu tài chính chờ; xóa đăng ký riêng; reset counter khi chốt PIN |
| [CardSynchronizer.java](C:/JavaCard/TTM_CuoiKy/backend/src/main/java/vn/edu/smartcard/backend/CardSynchronizer.java) | SYNCED của wallet DEBIT kéo theo PAID qua repairWalletRecords | Tách hoàn tất giao thức thẻ khỏi kết quả hóa đơn; thêm quyết định hủy, hoàn bù, phát hành kết quả cuối |
| [WalletService.java](C:/JavaCard/TTM_CuoiKy/backend/src/main/java/vn/edu/smartcard/backend/WalletService.java) | OPEN/PAID/CANCELLED; owner theo terminalId của tiến trình; callback chỉ tìm topup/thẻ còn tồn tại | Một attempt/hóa đơn; phiên quầy; hủy/hoàn bù; tra cả khoản chờ của thẻ đã xóa khi nhận callback |
| [ReaderHub.java](C:/JavaCard/TTM_CuoiKy/backend/src/main/java/vn/edu/smartcard/backend/ReaderHub.java) | RAM/context theo kết nối, snapshot allowlist | Giữ metadata tài chính cần recovery, chặn nghiệp vụ mới khi hoàn bù chưa xong, bổ sung allowlist/version snapshot |
| [StudentCardApplet.java](C:/JavaCard/TTM_CuoiKy/card/src/main/java/vn/edu/smartcard/card/StudentCardApplet.java) | PREPARE/COMMIT/ACK, CREDIT/DEBIT; chưa có đảo DEBIT có ràng buộc | Giữ metadata debit chưa quyết toán; REVERSAL theo operation gốc; FINALIZE/RELEASE sau quyết định thành công |
| [ApiClient.java](C:/JavaCard/TTM_CuoiKy/src/main/java/vn/edu/smartcard/ttm_cuoiky/ApiClient.java) | terminalId ngẫu nhiên mỗi lần mở | stationId ổn định theo quầy + stationSessionId mới mỗi lần mở |
| [CanteenFrame.java](C:/JavaCard/TTM_CuoiKy/src/main/java/vn/edu/smartcard/ttm_cuoiky/CanteenFrame.java) | Đóng cửa sổ chủ yếu eject/logout | Gửi close phiên quầy trước; không eject trước khi backend đã ghi nhận quyết định hủy và xử lý phục hồi cần thiết |
| [CheckoutView.java](C:/JavaCard/TTM_CuoiKy/src/main/java/vn/edu/smartcard/ttm_cuoiky/CheckoutView.java) | Có nút thử lại, mở lại hóa đơn cũ | Bỏ retry trên hóa đơn đã thất bại; lịch sử chỉ đọc; mở app mới bắt đầu đơn trống; hiển thị CANCEL_PENDING rõ ràng |
| [AdminView.java](C:/JavaCard/TTM_CuoiKy/src/main/java/vn/edu/smartcard/ttm_cuoiky/AdminView.java) | Một cảnh báo số dư khi delete | Hai cảnh báo theo thứ tự; xác nhận độc lập; báo mã hồ sơ đối chiếu nếu giữ khoản chờ |
| [Api.java](C:/JavaCard/TTM_CuoiKy/shared/src/main/java/vn/edu/smartcard/shared/Api.java), [Protocol.java](C:/JavaCard/TTM_CuoiKy/shared/src/main/java/vn/edu/smartcard/shared/Protocol.java), [CardClient.java](C:/JavaCard/TTM_CuoiKy/backend/src/main/java/vn/edu/smartcard/backend/CardClient.java) | DTO/state/APDU của V3 hiện có | DTO preview/phiên/outcome, capability tài chính và lệnh hoàn bù; không đổi KDF/scheme mã hóa hồ sơ |

Không đưa issuer, RSA/OAEP hoặc mã hóa đường truyền quay lại. Heartbeat quản lý tuổi thọ hóa đơn, không phải secure channel. Phiên PIN quầy quản lý vẫn giữ đến khi rút/ngắt thẻ, không bị TTL hóa đơn chi phối.

## 3. Xóa cứng và giữ khoản nạp để hoàn tiền

### 3.1. Định nghĩa “chưa đồng bộ” và cách đếm

Sau khi xác định trạng thái thẻ, tính N là số mục tài chính chưa xử lý xong, không phải số APDU, số lần retry hay số hàng journal.

Một yêu cầu nạp gốc chỉ được tính một lần, dù có nhiều bước xử lý/callback. Các nhóm cần xem xét:
- WAITING_BANK: vẫn đang chờ xác nhận; chưa được coi là tiền đã nhận.
- BANK_RECEIVED: có xác nhận nhưng chưa ghi vào thẻ.
- CREDIT nội bộ đang chờ: đối chiếu operation để biết đã ghi bao nhiêu; không đoán từ một status.
- REVIEW hoặc yêu cầu đã hết hạn nhưng còn sự kiện tiền vào chưa giải quyết.
- Khoản thu độc lập đã gắn đúng thẻ nhưng chưa gắn chắc chắn vào một yêu cầu nạp: một mục review cho mỗi bank transaction duy nhất.
- CREDITED chỉ được loại khỏi N khi không còn khoản tiền vào dư/khác cần review cho cùng yêu cầu.

Các callback trùng provider ID/bank reference không làm tăng N hoặc tiền cần hoàn. Tiền chưa được ngân hàng xác nhận không được tính vào số tiền được hoàn.

### 3.2. Luồng preview và xác nhận

1. Admin hợp lệ, đúng thẻ đang kết nối, ECC hợp lệ, lifecycle BLOCKED.
2. Đọc trạng thái thực trên thẻ và đối chiếu mirror/counter. Nếu có thao tác thẻ đang chờ, xác định kết quả của nó trước để tránh hoàn lại một khoản đã được ghi vào thẻ.
3. Kiểm tra số dư thẻ trước. Nếu số dư > 0, hỏi đúng: “Thẻ vẫn còn số dư! Xác nhận xoá?”
4. Kiểm tra N khoản nạp chưa đồng bộ. Nếu N > 0, hỏi tiếp: “Thẻ vẫn còn [số giao dịch] chưa đồng bộ! Tiếp tục xoá?” Ví dụ N=3 được hiển thị thành “Thẻ vẫn còn 3 giao dịch chưa đồng bộ! Tiếp tục xoá?”
5. Một lần preview có thể trả đủ hai nhóm dữ liệu, nhưng backend đọc/đối chiếu balance trước và UI hỏi theo đúng thứ tự. Hai cờ confirmBalance và confirmUnsynced độc lập; xác nhận số dư không thay cho xác nhận khoản chờ.
6. Admin hủy ở bất kỳ câu hỏi nào: không xóa đăng ký, không di chuyển dữ liệu tài chính.
7. Chỉ sau hai điều kiện xác nhận tương ứng mới gửi lệnh xóa có previewToken/revision, counter, walletVersion và tập khoản chờ đã được xem xét.
8. Backend đọc lại đúng thẻ; trong SQL transaction khóa card và các hàng tài chính liên quan, kiểm tra lại lifecycle, balance/version và nội dung khoản chờ. N không đổi nhưng số tiền/status/thành phần thay đổi cũng phải coi preview cũ là hết hiệu lực.
9. Nếu có thay đổi trước khi lấy được khóa: trả DELETE_STALE, lấy preview mới và hỏi lại theo dữ liệu mới. Không tự dùng xác nhận cũ cho giao dịch mới phát sinh.
10. Nếu chưa thể xác định số dư thực hoặc debit/reversal đang chưa rõ kết quả thì chưa đáp ứng bước kiểm tra số dư; không dùng cảnh báo N để bỏ qua trạng thái thẻ không xác định. Cho phép xóa với các khoản chờ đã phân loại được và giữ chúng để hoàn tiền.

### 3.3. Transaction lưu đối chiếu và xóa đăng ký

Trong cùng một SQL transaction:
- Tạo refund_case độc lập với bảng cards cho các mục cần giữ.
- Lưu tối thiểu cardId cũ làm tham chiếu, thời điểm/admin xóa, mã sinh viên/tên cần cho đối chiếu, số dư/phiên bản đã quan sát và các xác nhận. Không sao chép toàn bộ hồ sơ/ảnh.
- Di chuyển/copy có kiểm soát các mục nạp cần giữ sang refund_items, có ID/mã tham chiếu gốc duy nhất và số tiền/trạng thái gốc.
- Giữ các bank_events cần đối chiếu, liên kết chúng tới refund_item/refund_case. Gỡ FK tới cards/topups chuẩn bị bị xóa; không xóa rồi tạo lại sự kiện làm mất khóa chống trùng.
- Giữ chứng từ/metadata tài chính cần xác định phần đã nhận, đã ghi thẻ, còn chờ hoặc chưa rõ. Không tự biến khoản chưa xác định thành khoản được hoàn.
- Xóa cards, profile/ảnh/pending, S_backend, PIN attempts, wallet mirror, cipher snapshot và dữ liệu nghiệp vụ đã hoàn tất không thuộc ngoại lệ tài chính.
- Hủy các hóa đơn chưa có thao tác tiền của thẻ bị xóa; không để hóa đơn OPEN còn tham chiếu FK tới card bị xóa. Hóa đơn có debit/reversal chưa rõ kết quả phải được xử lý ở bước đối soát trước.
- Commit chỉ khi cả phần lưu đối chiếu và xóa đăng ký đều thành công. Nếu một phần lỗi, rollback cả hai phần DB.

Xóa quyền/khóa RAM trước khi mất S_backend; dọn file thẻ sau commit. Lỗi dọn file được báo riêng, không khôi phục đăng ký đã xóa.

refund_case/refund_items không có FK bắt buộc tới cards, không giữ PIN, S_card, S_backend, khóa AES, private key ECC hoặc raw snapshot. Backend tra cứu thẻ và các API sinh viên chỉ dựa trên đăng ký còn tồn tại; không dùng refund_case để đăng ký/khôi phục thẻ.

### 3.4. Phần tiền được hoàn và callback tới muộn

Giữ riêng:
- Số tiền đã được ngân hàng xác nhận.
- Số tiền đã chứng minh ghi vào thẻ.
- Số tiền chưa ghi thẻ, đủ điều kiện xem xét hoàn.
- Khoản chưa rõ, cần review; không gán số tiền hoàn phỏng đoán.

Số dư đã có trên thẻ được admin xác nhận bỏ khi xóa không tự cộng vào tiền hoàn của khoản nạp chờ.

Ví dụ: thẻ có 50.000đ, còn 30.000đ đã nhận ngân hàng chưa ghi thẻ. Sau hai cảnh báo, xóa đăng ký; hồ sơ đối chiếu giữ khoản 30.000đ chờ hoàn. Không tự ghi “cần hoàn 80.000đ”.

Webhook phải:
1. Xác thực như luồng hiện hành và giữ dedup toàn cục theo provider ID/bank reference.
2. Tra yêu cầu nạp còn hoạt động; nếu không còn thẻ/topup, tra mã tham chiếu đã giữ trong refund_items.
3. Nếu callback đang chờ khóa card trong lúc card bị xóa, sau khi lấy lại quyền đọc phải tra lại nhánh refund, không return sớm vì không còn card.
4. Ghi tiền vào hồ sơ đối chiếu của thẻ đã xóa; không CREDIT vào thẻ khác, không tự tạo lại card row, kể cả có thẻ mới cùng MSSV.
5. Callback trùng không tăng lần hai; callback khác/sai số tiền ghi REVIEW theo bằng chứng.
6. WAITING_BANK chưa có tiền về không đồng nghĩa đã nợ một khoản hoàn. Khi có xác nhận hợp lệ đến muộn, chuyển hồ sơ sang chờ hoàn/review theo dữ liệu thực.

Các trạng thái đề xuất: WAITING_BANK, REFUND_PENDING, REVIEW_REQUIRED, REFUNDED, CLOSED_NO_FUNDS. Cần giữ mã tham chiếu để callback muộn vẫn có thể được rà soát; CLOSED_NO_FUNDS không cho phép tự bỏ qua tiền vào hợp lệ về sau.

Đợt này lưu và cho admin tra cứu hồ sơ đối chiếu. Chuyển tiền ngân hàng tự động không nằm trong phạm vi. Thông tin người nhận hoàn phải được xác minh từ chứng từ/ngân hàng; không suy ra tài khoản người gửi từ trường tài khoản nhận tiền của hệ thống. Nếu bổ sung đánh dấu hoàn tiền thủ công, phải có số tiền/chứng từ/operator để ngăn hoàn trùng.

## 4. Admin đặt lại PIN và reset counter

Luồng:
1. Đăng nhập admin, đúng thẻ/ECC; kiểm tra lifecycle và đối soát như V3.
2. Chuẩn bị PIN record và hồ sơ mã hóa lại ở slot chờ.
3. Trước COMMIT_DECIDED mà thất bại: abort; PIN cũ và pin_failures cũ giữ nguyên.
4. Sau khi thẻ đã chốt PIN mới, transaction DB_COMMITTED đồng thời ghi cipher snapshot/version, kết quả operation và pin_failures=0.
5. Recovery/retry cùng operation thực hiện reset counter đúng một lần. Dùng phase hoặc cờ pin_counter_reset_applied bền vững; không quét các operation RESET_PIN đã SYNCED rồi reset counter lại.
6. Nếu sau reset thành công có một lần PIN sai mới, counter=1; việc đồng bộ/lấy lại biên nhận cũ không được đưa về 0 lần nữa.
7. Không đổi lifecycle BLOCKED thành ACTIVE như tác dụng phụ của reset PIN. Manual block thắng trạng thái sử dụng; khôi phục thẻ vẫn là thao tác riêng.
8. Giữ lịch sử pin_attempts; reset counter không xóa các lần thử cũ.

Ví dụ: counter 4 → admin reset thành công → 0 → người dùng nhập sai PIN mới → 1. Nếu thao tác admin bị abort trước commit thì counter vẫn 4.

## 5. Phiên quầy: đóng/mở ứng dụng thì hủy hóa đơn cũ

### 5.1. Các ID và quy tắc sở hữu

- stationId: định danh ổn định của từng quầy, lưu cấu hình theo quầy. Quầy quản lý và quầy thanh toán không dùng chung một ID chỉ vì cùng máy.
- stationSessionId: ID mới mỗi lần mở ứng dụng quầy thanh toán.
- sessionEpoch/fencing token: do backend quản lý, phiên cũ không thể hồi sinh bằng heartbeat/request tới muộn.
- connectionId: lần kết nối/insert thẻ; vẫn tách riêng với ID quầy và phiên ứng dụng.
- Hóa đơn thuộc một stationSessionId. Phiên mới không được tiếp quản để thanh toán hóa đơn cũ.

Đây là thay thế cho đề xuất “mở lại và tiếp tục hóa đơn còn hạn” trong lần rà trước. Lịch sử được xem ở chế độ chỉ đọc, không có nút Thanh toán/Tạo lại QR cho hóa đơn của phiên đã đóng.

### 5.2. Mở ứng dụng

1. UI gọi open-session với stationId và startup request ID idempotent.
2. Backend khóa định danh quầy, kết thúc các session cũ của đúng quầy và tăng epoch.
3. Hủy ngay các hóa đơn OPEN chưa có thao tác tiền của session cũ. Những hóa đơn đang có thao tác tiền nhận quyết định hủy theo mục 6, không bị gán CANCELLED giả.
4. Tạo session mới ACTIVE; trả session/epoch/deadline và các kết quả cũ chỉ để thông báo/lịch sử.
5. UI vào giỏ trống, không tự tải một hóa đơn cũ thành đơn đang thao tác. Muốn mua lại phải tạo invoiceId/QR mới.
6. Request từ session cũ bị từ chối trước khi xác thực PIN hoặc PREPARE tiền.

Không hủy hóa đơn của quầy khác. Hóa đơn đã PAID được giữ làm lịch sử; không đổi thành CANCELLED khi mở lại app.

### 5.3. Đóng bình thường

- Handler đóng cửa sổ gọi close-session trước, backend ghi CLOSED và yêu cầu hủy các hóa đơn chưa thành công của session đó.
- Phần ghi quyết định phải là SQL ngắn, không chờ lấy reader monitor/APDU mới đánh dấu phiên đã đóng.
- OPEN chưa có debit: CANCELLED ngay, lý do STATION_CLOSED; QR mất hiệu lực.
- Đang xác thực/chuẩn bị/ghi tiền: CANCEL_PENDING, xử lý abort/reversal theo trạng thái thực.
- Thu hồi quyền sinh viên và chặn lệnh mới ngay. Phần recovery của giao dịch cũ vẫn được phép chạy để hoàn số dư.
- Nếu thẻ còn kết nối, backend có thể hoàn tất hủy/hoàn bù dù UI đã đóng; không eject thẻ mô phỏng trước khi đã lưu quyết định và điều phối phần này.
- Nếu close API không phản hồi, UI không được khẳng định backend đã hủy xong; heartbeat và lần open tiếp theo là cơ chế phát hiện/dọn tiếp.

### 5.4. Tắt cưỡng bức, mất mạng hoặc backend restart

Không thể biết ngay thời điểm ứng dụng bị kill nếu không có tín hiệu. Đề xuất heartbeat độc lập EDT mỗi 5 giây, lease 15 giây do server tính:
- Close bình thường và open lại: backend xử lý ngay khi nhận yêu cầu.
- Mất tiến trình/mạng: lease hết hạn thì backend đóng session và hủy các hóa đơn chưa thành công.
- Dialog PIN hoặc UI busy không được làm ngừng heartbeat; chạy bằng worker riêng.
- Khi backend restart, các session cũ bị coi là đã kết thúc và được dọn trước khi nhận thanh toán mới; không đợi hóa đơn cũ đủ 5 phút.
- Heartbeat cũ không mở lại CLOSED session hoặc kéo dài QR cũ.

Bản hiện tại dùng cố định 5/15 giây trong ApiClient/StationSessions; không cam kết phát hiện crash tức thời. TTL này không áp dụng cho PIN session quầy quản lý.

## 6. Nguyên tử từ tạo hóa đơn đến kết quả thanh toán

### 6.1. “Quay về nguyên trạng” là gì?

Kết quả nghiệp vụ cuối chỉ có:
- PAID: hóa đơn được chấp nhận thanh toán, hai bên đã xác nhận trừ đúng một lần.
- CANCELLED: không còn khoản trừ ròng của hóa đơn; số dư thẻ và DB bằng trước khi thanh toán.

Hóa đơn đã tạo vẫn được giữ với trạng thái CANCELLED. Journal, ID/counter/version và bộ đếm PIN sai không bị xóa hoặc quay lùi để giả lập chưa có thao tác.

Không giữ một SQL transaction suốt thời gian chờ QR/PIN 5 phút. Tạo header + lines + tổng tiền của hóa đơn dùng một SQL transaction ngắn; toàn vòng đời dùng journal/quyết định bền vững và các transaction cục bộ.

[PostgreSQL ROLLBACK](https://www.postgresql.org/docs/current/sql-rollback.html) chỉ hoàn tác transaction DB; [JCSystem transaction](https://docs.oracle.com/en/java/javacard/3.1/jc_api_srvc/api_classic/javacard/framework/JCSystem.html) chỉ nguyên tử trên thẻ. Nếu thẻ đã trừ tiền rồi bị rút, không có cách hoàn số dư trên thẻ ngay lập tức. Cần hoàn bù khi kết nối lại, với trạng thái chờ công khai.

### 6.2. State machine

| Trạng thái hóa đơn | Ý nghĩa / thao tác cho phép |
|---|---|
| OPEN | QR còn hạn, chờ một lần thử thanh toán của đúng session |
| PAYMENT_PENDING | Có attempt; đang xác thực/chuẩn bị/chốt giao thức tiền, chưa được tính là đã bán thành công |
| CANCEL_PENDING | Quyết định hủy đã chốt; không cho thanh toán lại, đang abort/xác định debit/hoàn bù |
| PAID | Kết quả thành công bền vững sau xác nhận hai phía; chỉ xem lịch sử |
| CANCELLED | Chưa trừ hoặc đã hoàn bù đủ; hóa đơn hết hiệu lực, phải tạo hóa đơn mới |

Mỗi invoice có tối đa một payment_attempt. Retry cùng request chỉ lấy lại outcome của attempt đó; không tạo lần thử mới trên hóa đơn thất bại.

Người dùng đã xác nhận:
- PIN_WRONG → ghi counter, hủy hóa đơn, lần sau tạo mới. Năm lần sai có thể xảy ra qua năm hóa đơn; không reset vì hủy hóa đơn.
- Không đủ số dư → hủy hóa đơn, không tự quay lại OPEN.
- ECC sai/không đúng thẻ, hủy hộp PIN hoặc lỗi xác thực đã kết luận → hủy hóa đơn, không ghi tiền.
- Chưa đưa thẻ vào hoặc lỗi nhập định dạng bị chặn ngay tại form trước khi gửi attempt vẫn là chờ thao tác, không phải một giao dịch tiền đã thất bại.
- Hết hạn khi còn chờ/chưa quyết định commit → hủy. Một giao thức commit đã được cấp phép trước hạn có thể hoàn tất sau mốc QR nếu không có lỗi/hủy và session vẫn hợp lệ; QR không được dùng mở attempt mới.
- Close, lease lost hoặc lỗi trong khi đang ghi tiền mà chưa SUCCESS → yêu cầu hủy; xác minh/hoàn bù nếu cần.
- Mất phản hồi hoặc timeout không được tự gán “chưa trừ tiền”.

### 6.3. Hai loại quyết định phải tách riêng

1. COMMIT_DECIDED của card operation: điều phối một thay đổi cụ thể trên thẻ, không tự đảo quyết định giao thức này.
2. SUCCESS hoặc CANCEL của payment_attempt/invoice: kết quả nghiệp vụ, quyết định một lần dưới khóa/CAS.

Một khi CANCEL đã thắng, không được repair hóa đơn thành PAID chỉ vì thấy debit operation SYNCED. Nếu SUCCESS đã thắng và invoice PAID bền vững trước yêu cầu đóng/hủy, kết quả vẫn PAID, kể cả UI bị tắt hoặc mất phản hồi.

### 6.4. Luồng thành công

1. SQL tạo invoice + lines, chốt giá và expires_at; gắn session/epoch.
2. Card insert mới → tạo attempt → ECC → nạp ngữ cảnh → PIN.
3. PIN_OK chốt counter độc lập; ghi beforeBalance B0, beforeWalletVersion và originDebitOperationId trong journal.
4. PREPARE debit: active balance chưa đổi; lỗi tại đây abort.
5. Kiểm tra lại session/epoch, lifecycle, deadline và cancel flag trong transaction quyết định.
6. Chốt COMMIT_DECIDED; card COMMIT và backend mirror/ledger cùng được xác nhận. Invoice vẫn PAYMENT_PENDING.
7. ACK giao thức và kiểm tra hai phía; giữ metadata debit có thể hoàn bù đến khi có quyết định nghiệp vụ cuối.
8. SQL transaction khóa session → card → invoice → attempt/operation theo một thứ tự thống nhất; chỉ ghi SUCCESS/PAID nếu chưa CANCEL và cả hai phía đã xác nhận. Không thực hiện APDU trong transaction SQL này.
9. Gửi FINALIZE/RELEASE idempotent sau PAID để thẻ bỏ trạng thái giữ tài chính. Đây là dọn metadata, không trừ tiền lần nữa.
10. UI chỉ báo thành công theo PAID đã xác nhận. Nếu release còn chờ, recovery hoàn tất release trước nghiệp vụ mới; không hủy một hóa đơn PAID.

### 6.5. Luồng hủy / hoàn bù

| Lúc nhận quyết định CANCEL | Cách xử lý |
|---|---|
| Chưa có debit hoặc đã chứng minh chưa áp dụng | Hủy intent/ABORT candidate, không thay số dư, chốt CANCELLED |
| Card PREPARED, chưa có COMMIT_DECIDED | ABORT idempotent, xác nhận active balance cũ rồi CANCELLED |
| Đã COMMIT_DECIDED nhưng chưa biết card đã áp dụng chưa | Đọc journal/state; hoàn tất phần commit đã quyết định nếu cần rồi chuyển sang reversal; không tự sửa mirror về B0 |
| Đã trừ một/both phía, chưa SUCCESS | REVERSAL gắn debit gốc, hoàn đúng số đã trừ trên thẻ và DB rồi CANCELLED |
| Thẻ bị rút hoặc trạng thái chưa rõ | Giữ CANCEL_PENDING và yêu cầu kết nối đúng thẻ để đối soát/hoàn bù |
| Đã PAID trước khi yêu cầu hủy tới | Giữ PAID, trả lại kết quả thành công; hoàn tiền một giao dịch đã bán là nghiệp vụ khác, ngoài đợt này |

Ví dụ: B0=100.000đ, hóa đơn 35.000đ. Nếu hủy sau khi card đã về 65.000đ nhưng chưa SUCCESS, reversal đưa card và DB về 100.000đ, giữ debit + reversal trong ledger, chốt CANCELLED. Không xóa debit khỏi lịch sử hoặc trừ lại lần nữa.

### 6.6. Ràng buộc REVERSAL

- Là thao tác hệ thống hoàn tác debit của hóa đơn, khác nạp tiền ngân hàng và khác hoàn tiền ngân hàng cho thẻ bị xóa.
- Có operationId riêng, parent/original debit ID duy nhất; một debit chỉ có tối đa một reversal có hiệu lực.
- Amount do backend lấy từ debit đã đối chiếu, không nhận số tiền tùy ý từ UI.
- Thẻ giữ bền vững ID debit chưa quyết toán, amount/beforeBalance, version/counter và dấu đã đảo. ACK thông thường không xóa dữ liệu này trước quyết định SUCCESS/CANCEL.
- Card kiểm tra đúng debit gốc, đúng thẻ, số tiền/trạng thái/version; JCSystem transaction cập nhật balance + metadata nguyên tử.
- Backend mirror, ledger reversal, liên kết original và outcome được cập nhật bằng các SQL transaction ngắn, idempotent.
- Counter/version thẻ tăng theo thao tác thực tế, không quay về số cũ; số dư và tác động bán hàng ròng trở lại B0.
- Chặn nghiệp vụ thẻ mới trong khi financial hold/cancel pending; chỉ cho recovery của operation gốc.
- Recovery hoàn bù cần đúng thẻ và ECC, không bắt người dùng nhập lại PIN để hoàn một khoản trừ trước đó đã được cấp phép. Không mở một API nạp tiền không PIN; applet chỉ nhận reversal đúng debit đủ điều kiện.
- Manual block không cản hoàn bù thao tác cũ; thẻ vẫn BLOCKED sau đó. Không reset PIN counter khi reversal.
- Retry/lost ACK của reversal không cộng tiền lần hai. Mismatch → REVIEW/CONFLICT, không ghi đè balance cho khớp.

### 6.7. Hủy đồng thời với thanh toán

- CLOSE/lease-expiry phải ghi trạng thái session và cancel flag bằng DB trước, không chờ reader lock.
- Bước SUCCESS kiểm tra session/cancel dưới cùng thứ tự khóa và cập nhật có điều kiện.
- SUCCESS thắng trước → PAID. CANCEL thắng trước → abort/reversal, cuối cùng CANCELLED.
- Không giữ lock SQL qua APDU; worker phục hồi đọc quyết định bền vững trước mỗi chặng.
- DB/schema/API phải có constraint không cho cả SUCCESS và CANCEL cùng được chốt, hoặc cùng debit có hai reversal.
- UI guard callback theo session/epoch/invoice/attempt/request sequence, không để phản hồi OPEN cũ ghi đè PAID/CANCEL_PENDING/CANCELLED mới.

## 7. Schema/API/APDU và thứ tự nâng cấp

### 7.1. Dữ liệu dự kiến

| Nhóm | Trường/quan hệ cần có |
|---|---|
| station_sessions | station_id ổn định, session_id, epoch, ACTIVE/CLOSED/LOST, heartbeat_at/lease_until/closed_at |
| invoices | owner session/epoch, OPEN/PAYMENT_PENDING/CANCEL_PENDING/PAID/CANCELLED, cancel_reason, paid_at/cancelled_at, attempt link |
| payment_attempts | invoice_id UNIQUE, before/after balance/version, original_debit_id, SUCCESS/CANCEL decision, failure reason, reversal link |
| card_operations/wallet_operations | origin/parent operation, loại REVERSAL, protocol phase riêng outcome nghiệp vụ, reversal uniqueness |
| card state/snapshot | Debit hold, dữ liệu gốc cần reversal, marker đã đảo/finalize; version/capability và allowlist mới |
| refund_cases | Tham chiếu tài chính thẻ đã xóa, admin/time, dữ liệu nhận diện tối thiểu, balance observed, summary |
| refund_items | Nạp/bank item gốc, unique reference/ID, requested/received/credited/refundable amounts, status và chứng từ |
| bank_events | Giữ global provider/bank-reference uniqueness, link refund độc lập; active card/topup FK phải được gỡ trước hard delete |
| delete preview | Token/revision gắn đúng thẻ/connection, counter/walletVersion và fingerprint tập khoản chờ; hai xác nhận riêng |
| reset PIN | Marker/phase đảm bảo pin_failures reset đúng một lần khi DB_COMMITTED |

Tên bảng/trường đã chốt ở migration V4; chưa xác nhận áp dụng vào DB ứng dụng. Không tạo FK từ refund data buộc giữ lại card row.

API dự kiến:
- Open/heartbeat/close station session.
- Invoice create/prepare/pay/cancel/status có session/epoch/attempt idempotency.
- Delete preview/execute có pending count/revision và confirmUnsynced.
- Admin tra cứu refund cases/items độc lập card đăng ký.
- Recovery điều phối debit/reversal/release; không expose amount/operation tùy ý cho UI.

APDU cần mở rộng REVERSAL và FINALIZE/RELEASE, STATE/capability để đọc hold/đã đảo. Bản applet chỉ hỗ trợ V3 cũ phải bị chặn khỏi luồng yêu cầu reversal; không fallback sang CREDIT không ràng buộc. KDF, AES-CBC/HMAC, hai bí mật và ECC giữ nguyên.

### 7.2. Bảo vệ dữ liệu trước migration xóa V3

V3__ecc_demo_reset.sql hiện bắt đầu bằng DELETE card_operations/wallet_operations/topups và dữ liệu liên quan. Chỉ thêm V4 sau V3 không thể cứu dữ liệu đã bị V3 xóa.

Trước khi chạy nâng cấp:
1. Dừng backend/worker/quầy cũ, kiểm tra đúng CSDL project và Flyway version thực tế.
2. DB còn ở V1/V2: bắt buộc financial-retention preflight trước khi cho V3 chạy. Preflight tạo kho đối chiếu độc lập, lưu khoản chờ và bằng chứng, giữ/gỡ FK bank events theo đúng cách V3 sẽ dọn dữ liệu. Commit và kiểm đếm thành công trước destructive migration.
3. Chỉ sau khi preflight thành công mới chạy V3 rồi migration bổ sung V4. Có thể gắn preflight vào bootstrap/callback có kiểm soát trước migrate; phải có marker idempotent và chặn startup nếu chưa xác nhận lưu đủ dữ liệu.
4. DB đã ở V3: dùng V4 bổ sung; không sửa checksum V1/V2/V3 đã áp dụng, không chèn migration out-of-order để lách kiểm tra.
5. DB mới trống: không có dữ liệu cần giữ, vẫn kiểm tra cấu trúc kho đối chiếu ở V4.
6. Dữ liệu test fixture ở ttm_test có thể reset theo fixture. Không suy từ dữ liệu demo rằng mọi khoản trong CSDL ứng dụng là tiền giả.
7. Dọn file/issuer/backup V2 chỉ sau khi dữ liệu cần giữ đã có bản đối chiếu bền vững. Không dùng nguyên dump có hồ sơ/khóa làm kho hoàn tiền.
8. Bộ thẻ V3 đã được người dùng tạo, nếu có, không nằm trong quyền “xóa bộ V2” cũ. Kiểm tra/migrate snapshot/capability giữ identity/PIN/keys; không tự xóa để vượt lỗi format.
9. Nếu bộ demo V3 chưa được tạo như báo cáo hiện tại, tạo bộ mới theo capability/snapshot đã nâng cấp sau khi schema và kiểm thử đạt.

Nếu legacy pending CREDIT chưa xác định đã ghi vào thẻ bao nhiêu, giữ REVIEW_REQUIRED và xử lý trước khi kết luận số tiền hoàn; không đoán rằng toàn bộ BANK_RECEIVED đều chưa được ghi.

## 8. Các đợt thực hiện

1. **Chốt mô hình dữ liệu và retention preflight.** Migration V4, FK/index/unique, session/outcome/reversal model; giữ dữ liệu trước V3; không xóa dữ liệu ứng dụng trong bước test.
2. **Reset PIN và hard delete.** Reset counter đúng lúc/đúng một lần; preview hai cảnh báo; lưu tài chính/xóa đăng ký trong một transaction; callback muộn và admin tra cứu đối chiếu.
3. **Vòng đời quầy và hóa đơn.** ID ổn định/session/epoch/heartbeat; close/open/restart hủy hóa đơn cũ; một attempt/bill, PIN sai/FUNDS hủy; lịch sử chỉ đọc.
4. **Đồng bộ hủy thanh toán.** Applet/client/protocol/snapshot hold + reversal/release; coordinator tách protocol SYNCED khỏi PAID, race/cancel decision và recovery.
5. **Tích hợp và nghiệm thu.** Swing DTO mới, cảnh báo/readonly/pending rõ ràng; kiểm thử PostgreSQL thật + jCardSim + đóng/mở ứng dụng; sau đó mới nâng cấp DB ứng dụng/tạo demo và cập nhật KIEM-CHUNG.

Đợt 3 chưa được đánh dấu hoàn tất chỉ vì đã gán CANCELLED trong DB khi thẻ có thể đã trừ tiền. Toàn bộ chính sách hủy cần đợt 4.

## 9. Ma trận nghiệm thu tối thiểu

| Nhóm | Ca bắt buộc |
|---|---|
| Delete preview | Balance trước khoản chờ; balance=0/N>0; balance>0/N=0; cả hai >0; hủy ở từng cảnh báo không đổi DB |
| Delete execute | Confirm riêng; version/tập mục thay đổi; bank callback chen giữa preview và delete; rollback archive+delete nếu SQL lỗi |
| Refund retention | Đăng ký/share/ảnh/PIN data biến mất; khoản chờ vẫn tra được; không thể khôi phục thẻ từ refund case |
| Late webhook | Tiền về sau delete; callback trùng; callback đang chờ card lock khi delete commit; không CREDIT thẻ mới cùng MSSV |
| Refund amount | Chưa nhận tiền không coi đã hoàn; không hoàn lại tiền đã ghi card; khoản dư/REVIEW được giữ; balance đã bỏ không tự cộng vào refund |
| Admin PIN | Counter 4 → reset success 0; abort vẫn 4; sau đó sai 1 vẫn 1 qua replay/reconcile; manual block không tự mở |
| Session | Close bình thường hủy OPEN ngay; reopen cùng station hủy cũ; quầy khác giữ nguyên; kill/lease; heartbeat cũ không hồi sinh; backend restart |
| Invoice | Sai PIN/FUNDS/ECC/hủy PIN → hủy, tạo mới; bộ đếm PIN qua nhiều invoice; hóa đơn cũ readonly; giá/tổng server; QR 5 phút |
| Success/cancel race | Đóng quầy/hủy/expire đồng thời PREPARE/commit/ACK; chỉ một outcome, không vừa PAID vừa CANCELLED |
| Recovery trước debit | Abort tại CREATED/CARD_PREPARED; B0 hai bên; CANCELLED, không có debit ròng |
| Recovery sau debit | Lỗi/lost response sau card commit hoặc DB commit; CANCEL_PENDING khi thiếu thẻ; cắm lại đúng thẻ → reversal một lần → B0/CANCELLED |
| Final success | PAID đã bền vững nhưng UI mất phản hồi/đóng; vẫn PAID, không reversal |
| Reversal faults | Ngắt tại các mốc reversal; gửi lặp COMMIT/ACK/RELEASE; đúng parent/amount, sai thẻ/version bị từ chối; không cộng hai lần |
| Interleaving | Đang hold thì chặn nghiệp vụ mới; manual block không cản hoàn bù cũ nhưng vẫn giữ BLOCKED |
| Snapshot | Hold/đã đảo tồn tại qua restart; RAM/PIN/key dẫn xuất không phục hồi như phiên cũ; capability cũ không chạy lệnh không hiểu |
| Migration | DB <=2 được giữ khoản chờ trước V3; DB V3 nâng V4 không mất dữ liệu; không sửa checksum đã áp dụng; admin/menu/cấu hình/thẻ khác giữ đúng phạm vi |
| UI | Không báo hủy xong khi còn lệch số dư; phản hồi cũ không ghi đè outcome; không mở lại QR/attempt cũ; hai cảnh báo đúng thứ tự |

Hiện trạng triển khai: FinancialRetention/RefundStore/StationSessions/PaymentCoordinator, DTO/API/Swing, capability 4 và migration V4 đã có. Applet và render đã được kiểm thử; ma trận SQL ở trên chưa chạy được vì lỗi môi trường.

Nghiệm thu applet bằng jCardSim theo lựa chọn của người dùng. Các test tài chính phải dùng PostgreSQL thật; kết quả 38 ca của V3 hiện tại không thay thế ma trận 0.4 này. Không tuyên bố đã rollback tức thì một thẻ bị rút hoặc đã chuyển tiền hoàn ngân hàng khi mới lưu hồ sơ chờ.
