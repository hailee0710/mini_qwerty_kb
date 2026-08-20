# Mini Qwerty Keyboard

Bàn phím QWERTY 3 hàng gọn nhẹ cho Android (IME) tích hợp sẵn bộ gõ tiếng Việt **Telex** và chế độ **Smart Telex** thông minh, dựa trên từ điển ~40.000 từ.

**Không cần quyền nào. Không kết nối mạng.** Bàn phím chỉ đọc những gì bạn gõ và không gửi đi đâu.

## Tính năng

- **Smart Telex** — gõ tiếng Việt kiểu Telex, còn từ tiếng Anh hiện nguyên chữ. Trình kiểm tra hình dạng âm tiết + từ điển giữ tiếng Anh đúng chữ trong khi tiếng Việt tự gõ dấu trực tiếp.
- **Đầy đủ Telex** — cả 6 phím dấu, mọi tổ hợp nguyên âm, đặt dấu đúng chính tả, dấu giữa từ, gõ ba lần để hoàn tác.
- **QWERTY 3 hàng gọn** — 9 chữ cái hiếm nhất là phím phụ (chạm hai lần nhanh) nằm đúng vị trí QWERTY quen thuộc; phím dấu `X S F R J` đặt nơi người gõ Telex mong đợi.
- **Hàng số/ký tự** kèm ký tự phụ khi chạm hai lần.
- **Giao diện sáng/tối/theo hệ thống**, tùy chọn rung, kéo để chỉnh độ cao bàn phím, tùy chỉnh cửa sổ chạm đúp.
- **Độ chính xác khi gõ** — vùng nhấn tính theo phím gần nhất kèm bù vị trí ngón cái, rung ngay khi chạm, xử lý đa chạm để gõ nhanh không mất phím.
- **Không cần quyền đặc biệt** — không có gì để cấp, không có gì để lộ.

## Cài đặt

Tải `app-release.apk` mới nhất từ trang [Releases](https://github.com/hailee0710/mini_qwerty_kb/releases) và mở trên thiết bị.

Sau đó bật bàn phím: **Cài đặt → Hệ thống → Ngôn ngữ và nhập liệu → Bàn phím trên màn hình → Mini Qwerty** (màn hình cài đặt của ứng dụng có nút tắt mở nhanh).

## Gõ tiếng Việt kiểu Telex

Phím dấu:

| Phím | Dấu | Ví dụ |
|------|-----|-------|
| `s` | sắc | `masy` → `máy` |
| `f` | huyền | `maf` → `mà` |
| `r` | hỏi | `mar` → `mả` |
| `x` | ngã | `max` → `mã` |
| `j` | nặng | `maj` → `mạ` |

Tổ hợp nguyên âm (gõ hai lần cùng phím hoặc đúng chuỗi):

```
aa → â    ee → ê    oo → ô    aw → ă
ow → ơ    uw → ư    dd → đ    uow → ươ
```

Các âm đặc biệt: `iê` = `iee`, `uô` = `uoo`, `yê` = `yee`, `uâ` = `uaa`, `uyê` = `uyee`, `ươ` = `uow`.

Gõ thêm lần thứ ba chữ cái cuối của tổ hợp để hoàn tác — từ trở về dạng thường:
`dooor` → `door`, `goood` → `good`, `uww` → `uw`, `uoww` → `uow`, `forr` → `for`.

Gõ hai lần cùng phím dấu để bỏ dấu; gõ phím dấu khác sẽ thay dấu cũ.

## Smart Telex

Khi bật Smart Telex, kết quả gõ Telex được kiểm tra hình dạng âm tiết tiếng Việt. Hình dạng không hợp lệ sẽ giữ nguyên chữ thường, nên các từ tiếng Anh như `cluster`, `good`, `pool` hiện đúng chữ khi gõ. Khi kết thúc từ, kết quả còn được đối chiếu với từ điển ~40.000 từ tiếng Việt.

Các trường hợp không xử lý được (giống auto-restore của ibus-bamboo): `door`, `bus`, `this` vẫn bị gõ dấu vì dạng có dấu là từ có thật trong từ điển. Hãy dùng dạng hoàn tác: `dooor`, `buss`, `thiss`, `forr`.

## Bố cục bàn phím

- **Hàng 1:** `X(Q) W(?) E R T H(Y) U I(P) O ,(.)` — phẩy trên, chấm dưới.
- **Hàng 2:** `A S(Z) D F(C) G(V) N(B) J(K) M(L)` + phím xóa.
- **Hàng 3:** shift, `123`, cách, enter.
- **Hàng số:** chữ số, `@ ! % : ) - ? = / ]` kèm ký tự phụ `~ # $ & ( _ + ; ' [`; cùng `.` và `📋` (clipboard).

Chạm nhanh hai lần vào phím sẽ thay ký tự cuối bằng ký tự phụ của phím đó. Giữ lâu (350 ms) hoặc vuốt xuống dứt khoát để gõ ký tự phụ. Chạm đúp Shift để bật caps lock (⇪). Giữ phím xóa để xóa liên tục. Gõ nhanh an toàn: hai phím chạm chồng nhau (ngón sau chạm trước khi ngón trước nhấc) vẫn gõ được cả hai, và ngón cái trượt nhẹ khi chạm sẽ không kích hoạt vuốt hay chạm đúp.

## Cài đặt

- Chủ đề: theo hệ thống / sáng / tối
- Bật/tắt rung
- Bật/tắt Smart Telex
- Cửa sổ chạm đúp (100–500 ms)

## Biên dịch

Yêu cầu: JDK 17, Android SDK, Gradle 8.5.

```bash
gradle wrapper --gradle-version 8.5   # nếu thiếu ./gradlew (không commit)
./gradlew assembleDebug               # APK debug
./gradlew :app:testDebugUnitTest      # kiểm thử (77 bài: Telex, dấu, nguyên âm, Smart Telex, mô phỏng xóa)
./gradlew bundleRelease               # AAB release để phát hành
```

Ký release đọc thuộc tính `miniqwerty.*` từ `~/.gradle/gradle.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); nếu thiếu thì bản release không ký — nên bản clone thuần vẫn biên dịch được.

APK: `app/build/outputs/apk/`, AAB: `app/build/outputs/bundle/release/`.

## Quyền riêng tư

Manifest không khai báo `uses-permission` nào. Không quyền internet, không telemetry, không phân tích. Phím bấm không bao giờ rời khỏi thiết bị.

## Giấy phép

[MIT](LICENSE)
