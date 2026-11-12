# NoteLab

App ghi chú một màn hình, gồm 4 file Java và 1 file Kotlin cho coroutine.

## 5 case demo

| Chức năng | Cơ chế | Cách thao tác | Code |
|---|---|---|---|
| Mở danh sách đã lưu | AsyncTask | Mở app hoặc chọn ⋮ → Tải lại danh sách. Loading hiện trong lúc đọc, sau đó hiển thị note. | `MainActivity.load()` |
| Xuất một note `.txt` | Thread + Handler | Chạm note → Xuất .txt → chọn nơi lưu. Thread ghi UTF-8, Handler báo kết quả lên UI. | `MainActivity.exportNote()` |
| Sao lưu khi tự động lưu | synchronized | Bật nhật ký luồng và lưu chậm trong ⋮ → Cấu hình Lab. Sửa note, ngừng gõ 0,6 giây, bấm Sao lưu ngay trong lúc đang ghi. | `NoteStore.save()`, `NoteStore.backup()` |
| Tìm trong N hoặc tất cả note | ExecutorService | Chọn 1–16 workers và giới hạn số note trong ⋮ → Cấu hình Lab, rồi nhập từ khóa và bấm Tìm. | `NoteSearch.find()` |
| Lấy gợi ý qua API | Kotlin coroutine | Bấm Gợi ý hôm nay, vuốt danh sách hoặc mở note trong lúc server chờ 6 giây. Kết quả xuất hiện bên cạnh nút. | `ApiDemo.kt`, `MainActivity.callApi()` |

Mở **⋮ → Hiện nhật ký luồng** để xem tên luồng và các bước xử lý (đồng thời hiển thị chi tiết workers/ms khi tìm kiếm). Logcat dùng tag `NoteLab`.
Có thể chỉnh thông số thử nghiệm tại **⋮ → Cấu hình Lab (Đa luồng)** và thêm dữ liệu qua **⋮ → Thêm 160 ghi chú mẫu**.

### API mẫu

Chạy lệnh sau trên máy tính, từ thư mục `NoteLab`, và giữ cửa sổ chạy trong lúc demo.

```powershell
python tools/demo-api.py
```

Nút gọi mặc định dùng `http://10.0.2.2:8765/suggestion` trên Android Emulator.
API mẫu lắng nghe localhost của máy tính, thực sự chờ 6 giây rồi trả JSON.
Có thể đổi URL qua **⋮ → Cấu hình Lab (Đa luồng)** để dùng backend khác.
Backend cần trả HTTP 200 và JSON UTF-8 theo mẫu sau.

```json
{"message":"Hôm nay bạn muốn ghi nhớ điều gì?"}
```

Trên điện thoại thật, nhập URL backend mà điện thoại truy cập được.
Phần gọi HTTP nằm trong `withContext(Dispatchers.IO)`, callback hiển thị kết quả chạy trên Main.
Thời gian hiển thị được đo từ khi bắt đầu gọi tới khi nhận xong dữ liệu.
App có xử lý lỗi kết nối, HTTP lỗi, timeout và hủy coroutine khi Activity bị hủy.
API có timeout kết nối 5 giây, đọc 15 giây, giới hạn phản hồi 4096 ký tự.

Tham khảo [hướng dẫn coroutine của Android](https://developer.android.com/kotlin/coroutines/coroutines-adv).

### Sao lưu và tìm kiếm

**Sao lưu** tạo bản sao tất cả note vào bộ nhớ riêng của app.
Hai tác vụ tự động lưu và sao lưu dùng chung `fileLock`. Log hiển thị lúc chờ, vào và nhả khóa.
Tùy chọn **Lưu chậm 1,5 giây** (trong **⋮ → Cấu hình Lab**) giúp quan sát lúc backup phải chờ ghi hoàn tất.

```text
/data/user/0/com.example.notelab/files/notes.json
/data/user/0/com.example.notelab/files/notes-backup.json
```

Tìm kiếm xét N note đầu tiên trong danh sách hoặc toàn bộ khi để trống N.
Số workers thực tế tối đa bằng số note cần xét. Kết quả được gộp theo thứ tự note ban đầu.
Khi so sánh thời gian, giữ nguyên dữ liệu, từ khóa, phạm vi và chạy nhiều lần.

## Build và chạy

Mở thư mục `NoteLab` trong Android Studio, dùng JDK 17 trở lên và SDK 36.
Build bằng công cụ cục bộ với lệnh sau.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-local.ps1 `
  -SdkPath 'C:\Users\ASUS\AppData\Local\Android\Sdk' `
  -JavaHome 'E:\Android Studio\jbr' -Tests
```

Script dùng Gradle để biên dịch Java/Kotlin và đóng gói coroutine, tạo `build/NoteLab-debug.apk`.
Khóa ký debug được giữ trong `build/debug.keystore` để cài đè và giữ dữ liệu.
Phiên bản 1.0.1 có versionCode 4. Android 9 trở lên.

```powershell
adb install -r build\NoteLab-debug.apk
adb install -r build\NoteLab-tests.apk
adb shell am instrument -w com.example.notelab.tests/com.example.notelab.SmokeTests
```

Bộ kiểm thử dùng dữ liệu riêng trong cache, kiểm tra 4 case cũ và HTTP chờ 6 giây,
vuốt danh sách bằng thao tác chạm trong lúc chờ, luồng IO/Main, UTF-8 và HTTP lỗi.

Mở máy ảo bằng script sau.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\open-emulator.ps1
```
