# Hướng dẫn sử dụng DevMap (cho PMO)

DevMap là một file `devmap.jar` chạy trên máy PMO: vừa là dòng lệnh, vừa là trang web ở `http://localhost:7070`.
PMO nhập yêu cầu, agent SA phân tích, PMO chốt, agent Dev sửa code trong worktree riêng, agent SA kiểm chứng, PMO xác nhận push.
Agent không bao giờ push và không đọc được file bí mật.

## 1. Cài đặt

- Java 17 trở lên (`java -version`).
- Git trong PATH (fetch/push dùng git của máy, nên dùng luôn quyền truy cập Git sẵn có).
- Claude Code (`claude --version`), đã đăng nhập. Khoá API do Claude Code giữ; DevMap không lưu khoá nào.
- Công cụ build của từng dự án nếu muốn chạy quality gate (Maven/Gradle/npm…).

Thêm thư mục `bin/` của DevMap (chứa `devmap.cmd` và `devmap`, cạnh `devmap.jar` hoặc trong repo có `target/devmap.jar`) vào PATH. Từ đó `devmap …` thay cho `java -jar devmap.jar …`.

Kiểm tra: `devmap doctor`.

## 2. Thêm dự án

```sh
devmap init nyxhub --data D:/devmap-data \
  --repo D:/code/order-service@test --repo D:/code/payment-service@main
```

- `--repo đường_dẫn@nhánh_nền`: nhánh nền là nhánh Dev luôn pull trước khi làm (mặc định `main`).
- `--git`: biến folder dữ liệu thành repo git (để đồng bộ giữa các máy).
- Cũng thêm được trên trang chủ (form "Thêm dự án").

DevMap không tự quét code khi thêm dự án. Muốn có graph đầy đủ ngay: màn hình **Graph** → "Quét toàn bộ dự án" (một lần, chỉ trích chữ ký, không gửi gì cho agent), hoặc `devmap index nyxhub --full`.
Sau đó chỉ mục tự cập nhật theo git diff mỗi lần SA làm việc.

## 3. Chạy

```sh
devmap                  # = devmap serve: chạy server và tự mở trình duyệt
devmap serve --port 7071 --no-open
```

Trình duyệt tự mở đúng link có `?token=…` (token đổi mỗi lần chạy). Gõ `devmap` lần nữa khi server đang chạy thì chỉ mở lại trang. Mỗi dự án một tab: `localhost:7070/nyxhub`, `localhost:7070/lendbiz`.

- Tiêu đề tab: `(số việc chờ PMO) tên dự án · trạng thái`. Biểu tượng có chấm xanh (agent đang chạy), vàng (chờ PMO), đỏ (lỗi).
- Bấm "Bật thông báo" một lần để nhận thông báo trình duyệt khi agent cần PMO quyết, chạy xong hoặc lỗi; bấm thông báo để mở đúng ticket.
- Đóng tab không dừng agent. Tắt `serve` bằng Ctrl+C; nếu agent còn chạy, DevMap cảnh báo và cần Ctrl+C lần nữa trong 10 giây.

## 4. Một ticket từ đầu đến cuối

1. **Tạo ticket** (nút "Ticket mới" hoặc `devmap task new nyxhub --type FEAT --code NYX-231 --request "…"`). Viết tự nhiên; DevMap tự bỏ câu xã giao, che bí mật, tạo `spec.json`. Agent chỉ nhận `spec.json`, không nhận nguyên văn.
2. **Hỏi SA** trong tab Chat, ví dụ "Phân tích và gửi chốt". Mỗi câu hỏi là một phiên agent mới; agent đọc tài liệu của ticket, không đọc lại chat. SA viết `analysis.md` (AC), `plan.md` (việc theo repo, tên file) rồi chuyển "Chờ PMO chốt".
3. **Chốt giải pháp** ở cột phải (hoặc "Yêu cầu phân tích lại"). Sau khi chốt, plan.md không ai sửa được ngoài lần phân tích lại.
4. **Triển khai**: hỏi Dev (ở trạng thái Sẵn sàng vai mặc định là Dev). Dev pull nhánh nền vào worktree riêng của ticket, sửa code, chạy quality gate, commit `<mã>: …`, chuyển SA.
   - Sửa DB schema, dependency hay thư mục cấm: hiện thẻ duyệt, chỉ PMO duyệt được.
   - PMO có thể tự sửa/commit trong worktree (đường dẫn ở cột phải); phiên Dev sau được báo.
5. **SA kiểm chứng** tự chạy: đối chiếu AC với diff, ghi `verify.md`. Lệch phạm vi hoặc chữ ký bị đổi mà nơi gọi chưa kiểm → trả về Dev. Đạt → "Chờ PMO xác nhận".
6. **Xác nhận push**: DevMap push đúng commit đã qua gate và kiểm chứng lên nhánh của ticket (không bao giờ vào nhánh bảo vệ). Ticket sang Done; ngữ cảnh phiên bị xoá, tài liệu giữ lại.
7. **Báo bug / Bổ sung logic** sau deploy: mở lại cùng mã, đi lại từ SA phân tích.

Ticket MAINT: xếp thứ tự trong khung "Hàng đợi" ở cột trái (↑↓) rồi bấm **Chạy hàng đợi**; Dev làm lần lượt, ticket sau bắt đầu khi ticket trước đã qua SA kiểm chứng. Ticket nào lỗi hoặc bị trả về thì hàng đợi dừng.

Tick "Quét toàn bộ dự án cho câu này" khi graph thiếu quan hệ: quét đúng một lần cho câu hỏi đó (có hộp báo số file trước khi chạy).

## 5. Các màn hình khác

| Màn hình | Dùng để |
| --- | --- |
| Graph | xem service → API → hàm, blast radius của ticket, thêm edge tay, quét chỉ mục |
| Rule và coding convention | rule theo dự án/service (`.devrules.yml`: mẫu commit, branch bảo vệ, lệnh phân tích tĩnh và unit test, thư mục cấm); convention trích từ code, PMO sửa và duyệt |
| Cấu hình dự án | repo, nhánh nền, service, quy ước mã ticket, mẫu branch, mẫu che bí mật và danh sách cho phép |
| Nhật ký | mọi thao tác của PMO, SA, Dev và DevMap: thời gian, ticket, repo, service, rule áp dụng (`logs/audit.jsonl`) |

Ở mỗi tin của agent: "Đầu vào agent" cho thấy đúng những gì agent đã nhận (đã che bí mật); dòng token cho biết số token và chi phí của câu đó; cột phải có tổng theo ticket và dự án.

## 6. Quality gate

Gate chạy cho mọi service mà ticket chạm: kiểm tách lớp Controller → Service → Repository (chỉ chặn vi phạm trong file ticket sửa; vi phạm cũ được liệt kê để làm ticket riêng), lệnh phân tích tĩnh, unit test.
Lệnh lấy từ rule của service (preset theo loại dự án). Nếu máy không có `mvn` trong PATH, sửa lệnh trong màn hình Rule, ví dụ `mvnw.cmd -q test`.
Kiểm bằng dòng lệnh: `devmap rules check nyxhub --repo order-service`.

## 7. Dòng lệnh

| Lệnh | Việc |
| --- | --- |
| `init`, `workspace add/list/remove` | thêm, liệt kê, bỏ đăng ký dự án (bỏ đăng ký giữ nguyên dữ liệu) |
| `serve` | chạy giao diện |
| `index <dự án> [--full]` | cập nhật chỉ mục theo git diff hoặc quét toàn bộ |
| `task new/list/show` | tạo và xem ticket |
| `rules show/check` | xem rule, chạy gate |
| `prompt <dự án> <ticket> [--role sa\|dev] [--question …]` | xem trước đúng nội dung một phiên agent sẽ nhận, không gọi LLM |
| `doctor` | kiểm môi trường và cấu hình |

## 8. Cấu hình máy

Truyền bằng `-D` trước `-jar`, ví dụ `java -Ddevmap.agent.max-concurrent=3 -jar devmap.jar serve`, hoặc với launcher: `set DEVMAP_OPTS=-Ddevmap.agent.max-concurrent=3` rồi `devmap`.

| Khoá | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `devmap.home` | `~/.devmap` | danh sách dự án, worktree, nhật ký phiên |
| `devmap.agent.max-concurrent` | 2 | số agent chạy cùng lúc trên máy; phiên sau chờ lượt |
| `devmap.agent.command` | `claude` (`claude.cmd` trên Windows) | lệnh Claude Code |
| `devmap.agent.model` | (mặc định của Claude Code) | model cho agent |
| `devmap.agent.timeout-minutes` | 30 | thời gian tối đa một phiên |
