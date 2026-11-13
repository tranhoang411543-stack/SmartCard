# jCardSim

`jcardsim-3.0.6.0.jar` được sao chép từ thư viện đã có tại `C:\JavaCard\BT2\lib`, không thay đổi nội dung jar.

SHA-256: `DB6DE7FFDE71651C45D00DF7E160771685CEC3E14E444BF36B796658D289942F`.

Maven metadata trong jar ghi `com.klinec:jcardsim`. jCardSim dùng Apache License 2.0; các license/notice thư viện con được giữ trong jar. Dự án gốc: https://github.com/licel/jcardsim .

Compatibility code của backend thay RNG chưa được seed trong bản fork này bằng CSPRNG của JDK và bật `com.licel.jcardsim.randomdata.secure=1`. Không sửa jar và không đóng gói lớp host này vào applet. Kiểm thử `separateSimulatedCardsHaveDifferentKeysAndIndependentState` bắt buộc xác nhận khóa của hai thẻ khác nhau.
