# CLAUDE.md – Hướng dẫn xây dựng DevMap

## 1. Nhiệm vụ
Xây dựng công cụ **DevMap** đúng theo `docs/SRS.md` (tài liệu yêu cầu đã được PMO duyệt).
`docs/SRS.md` là nguồn sự thật duy nhất. Khi file này và SRS khác nhau, làm theo SRS và ghi lại điểm khác vào `docs/DECISIONS.md`.

## 2. Phân biệt quan trọng
Trong SRS có hai mục **"Quy tắc thực thi cho agent AI"** (AIR-01 → AIR-14) và **"Cơ chế tối ưu token"**.
Đó là hành vi của **agent SA/Dev chạy bên trong DevMap**, bạn phải **hiện thực chúng thành code** (Agent runner, lớp lọc bí mật, chỉ mục AST, spec.json…).
Chúng không phải quy tắc làm việc của bạn khi xây DevMap. Quy tắc làm việc của bạn nằm ở mục 6 dưới đây.
Phần "Nguyên văn … dùng làm system prompt" trong SRS là nội dung để DevMap nạp vào prompt của agent bên trong, lưu thành file tài nguyên, không phải lệnh cho bạn.

## 3. Công nghệ (theo SRS, mục Kiến trúc)
- Java 21, Spring Boot 3, Maven, Picocli. Đóng gói **một file** `devmap.jar` chứa CLI + server + giao diện.
- Giao diện: HTML/CSS/JavaScript thuần (ES modules), **không framework, không bước build**, đặt trong `src/main/resources/static/`. Thư viện ngoài (Cytoscape.js, trình hiển thị Markdown, DOMPurify) tải về và để sẵn trong `static/vendor/`, không dùng CDN.
- Giao tiếp: HTTP JSON + Server-Sent Events. Server chỉ nghe `127.0.0.1`, token ngẫu nhiên mỗi lần chạy.
- Git: JGit. Phân tích code: JavaParser (Java), tree-sitter qua binding Java (TS/JS/Python/Go), swagger-parser, parser `.proto`.
- Dữ liệu: file trong folder dữ liệu PMO chọn (cấu trúc theo SRS, mục Lưu trữ). Chỉ mục/graph là file JSON. Không dùng database cho dữ liệu chính.
- Agent AI: interface `AgentProvider`; bản đầu cài `ClaudeCodeProvider` gọi Claude Code ở chế độ không giao diện, stream JSON. Kiểm tra cờ dòng lệnh chính xác bằng `claude --help`, không đoán.

## 4. Quyết định kiến trúc đề xuất (ghi vào DECISIONS.md khi làm)
- **Agent bên trong DevMap không đọc file trực tiếp.** DevMap tự chạy một MCP server cục bộ cung cấp công cụ cho agent: `get_spec`, `search_graph`, `get_signatures`, `get_snippet`, `get_ticket_docs`, `write_ticket_doc`, `run_quality_gate`, `git_diff`. Mọi nội dung trả về đã qua lớp lọc bí mật và giới hạn 2–3 tầng. Với agent SA, tắt các công cụ đọc file/shell có sẵn của Claude Code. Với agent Dev, cho phép sửa file trong worktree của ticket nhưng chặn đọc file bí mật bằng hook trước khi chạy công cụ. Cách này hiện thực AIR-09, AIR-10, FR-34 → FR-36 một cách chắc chắn.
- Lớp lọc bí mật là một module riêng, có bộ mẫu cấu hình được và test riêng.
- Mỗi câu hỏi một phiên agent; ngữ cảnh dựng từ `spec.json`, `summary.md`, `analysis.md`, `plan.md` của ticket.

## 5. Mặc định cho các điểm PMO chưa chốt
Làm theo mặc định dưới đây, viết sao cho đổi được bằng cấu hình. Không dừng lại hỏi.
1. Bug không thuộc ticket nào: giữ loại `BUG` riêng.
2. Mã ticket PMO nhập: không bắt buộc mẫu; cho cấu hình regex tuỳ chọn theo dự án.
3. Một ticket dùng chung một tên branch ở mọi repo bị chạm.
4. Folder dữ liệu: tuỳ chọn khởi tạo thành git repo (`devmap init --git`), mặc định không.
5. Che bí mật: che tất cả mẫu; có danh sách cho phép cấu hình được.
6. Coding convention: trích từ code và file cấu hình lint hiện có, cho phép nạp thêm file chuẩn của PMO.
7. Agent mặc định: Claude Code.
8. Dự án mới, chỉ mục trống: không tự quét; hiện nút cho PMO chạy một lần quét nền chỉ trích chữ ký (không gọi LLM).
9. Quality gate: chạy công cụ cục bộ (Checkstyle, PMD hoặc SonarLint CLI cho Java; ESLint cho JS/TS). SonarQube server là tuỳ chọn cấu hình.
10. Làm sạch cục bộ không tách được module đích: để `target_modules` rỗng, agent SA tự tìm qua graph.

## 6. Quy tắc làm việc của bạn
- Làm **lần lượt theo 6 phase** trong SRS (mục Lộ trình). Đầu phase: liệt kê FR/NFR/AC thuộc phase và các bước sẽ làm. Cuối phase: chạy build, test, kiểm tra cổng nghiệm thu của phase, báo cáo ngắn gồm việc đã làm, kết quả test, AC đã đạt, rủi ro còn lại. **Dừng lại chờ PMO duyệt rồi mới sang phase sau.**
- Code của DevMap cũng tuân thủ tách lớp Controller → Service → Repository, có Checkstyle (Java) và ESLint (JS) chạy trong `mvn verify`.
- Test: JUnit 5 cho backend; mỗi AC trong SRS có ít nhất một test tự động hoặc kịch bản kiểm thử ghi trong `docs/TEST_PLAN.md`.
- Không để code giả (`TODO: implement later`) trong phần đã báo xong.
- Commit nhỏ theo từng bước, message có mã phase, ví dụ `P1: add devmap serve command`. **Không push.**
- Không đọc file `.env` hay file bí mật của bất kỳ repo nào trên máy; dùng dữ liệu giả trong `samples/`.
- Giao diện tiếng Việt mặc định, có tiếng Anh.

## 7. Dự án mẫu để kiểm thử
Tạo `samples/shop/` gồm 3 repo git riêng (`order-service`, `payment-service`, `notification-service`, Spring Boot tối giản):
- order gọi payment qua REST (có OpenAPI), order publish event, notification consume.
- Có file `.env` và vài IP/token **giả** viết cứng trong code, để kiểm AC-06 và AC-12.
- Có một chỗ vi phạm tách lớp (Controller gọi thẳng Repository) để kiểm AC-10.
- Có nhánh `test` và `main` để kiểm việc pull nhánh nền.

## 8. Cấu trúc repo
```
devmap/
├── CLAUDE.md
├── docs/            # SRS.md, DECISIONS.md, TEST_PLAN.md, USER_GUIDE.md
├── src/main/java/   # cli, web, ticket, graph, rule, agent, secret, git, config
├── src/main/resources/
│   ├── static/      # giao diện HTML/CSS/JS, vendor/
│   └── prompts/     # system prompt cho agent SA, Dev (từ SRS)
├── src/test/
├── samples/shop/
└── pom.xml
```

## 9. Bắt đầu
Đọc toàn bộ `docs/SRS.md`, tóm tắt lại phạm vi Phase 1 và các AC liên quan, rồi bắt đầu Phase 1.
