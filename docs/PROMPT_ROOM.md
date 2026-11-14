# Bổ sung tính năng: Room SA + Dev và đồng bộ tự động giữa các agent

> Dán file này cho agent trong repo `devmap/`. Agent đã có `CLAUDE.md`, `docs/SRS.md`; prompt này mô tả phần bổ sung.

## 0. Trước khi code
1. Đọc lại `CLAUDE.md`, `docs/SRS.md`, `docs/DECISIONS.md` và các file `docs/CHANGE-*.md` đã có.
2. **Khảo sát source hiện tại**: máy trạng thái ticket, Agent runner (mỗi câu một phiên), cách dựng ngữ cảnh từ `spec.json`/`summary.md`/`analysis.md`/`plan.md`, cơ chế version tài liệu, luồng sự kiện SSE, giao diện chatbox 3 cột, thông báo khay hệ thống (nếu đã có). Không giả định; bám theo code đang có.
3. Viết kế hoạch ngắn, ghi quyết định vào `docs/DECISIONS.md`, rồi làm luôn.

## 1. Mục tiêu
- **Mỗi task là một room** gồm 3 thành viên: PMO, agent SA, agent Dev. PMO trao đổi trong room; câu thuộc phần việc của ai thì người đó nhận, tự làm và đưa đề xuất.
- **Các agent tự đồng bộ với nhau qua sự kiện**: khi một bên phát sinh thông tin mới (ví dụ Dev code xong phát hiện bug và đề xuất), bên kia tự cập nhật phần tài liệu của mình mà PMO không phải chuyển lời.
- Giữ nguyên các nguyên tắc đã có: luồng trạng thái và cổng duyệt của PMO, mỗi câu một phiên, ngữ cảnh 2–3 tầng chỉ gồm chữ ký và đoạn code, che bí mật, không đọc lại chat.

**Agent trao đổi với nhau qua tài liệu và sự kiện, không qua hội thoại.** Room chỉ là nơi hiển thị cho PMO.

## 2. Yêu cầu chức năng

### 2.1 Room
| Mã | Yêu cầu |
|---|---|
| FR-71 | Chatbox của mỗi ticket trở thành room có 3 thành viên với avatar và tên: PMO (bên phải), SA, Dev (bên trái, màu khác nhau). Thanh trên room hiện trạng thái ticket và agent nào đang chạy. |
| FR-72 | **Bộ định tuyến cục bộ, không gọi LLM**, quyết định ai nhận câu của PMO theo thứ tự: (1) PMO gọi tên `@SA`, `@Dev`, `@all`; (2) từ khoá và loại câu: nghiệp vụ, phân tích, nguyên nhân bug → SA; sửa code, chạy test, log lỗi kỹ thuật → Dev; (3) trạng thái ticket. Không chắc chắn thì hiện câu hỏi một dòng có hai nút "Gửi SA" / "Gửi Dev". Bộ từ khoá cấu hình được. |
| FR-73 | Quyền theo trạng thái ticket, kiểm ở server: trước khi PMO chốt giải pháp, Dev chỉ được **trả lời và ghi đề xuất**, không được sửa code; sau khi chốt, Dev được sửa code trong worktree. Cấu hình `room.dev_can_propose_before_approval` (mặc định `true`). |
| FR-74 | `@all` hoặc giao hai việc trong một câu (ví dụ "@SA rà nghiệp vụ, @Dev dò log") thì mở hai phiên độc lập chạy song song, kết quả về cùng room. Tuân theo giới hạn số agent đồng thời đang có. |

### 2.2 Đề xuất có cấu trúc
| Mã | Yêu cầu |
|---|---|
| FR-75 | Agent ghi phát hiện và đề xuất vào `tasks/<mã>/proposals/<thời điểm>-<tác giả>.json` theo schema: `id`, `author` (SA/Dev), `type` (`BUG_FOUND`, `SOLUTION_CHANGE`, `SCOPE_CHANGE`, `QUESTION`), `summary`, `location` (repo, file, chữ ký method), `proposal`, `affects` (`TECHNICAL_ONLY`, `AC`, `BUSINESS_RULE`, `SCOPE`), `refs` (đoạn tài liệu liên quan), `status` (`OPEN`, `APPLIED`, `REJECTED`, `NEEDS_PMO`). Nội dung đã che bí mật. |
| FR-76 | Room hiển thị mỗi đề xuất thành thẻ: tác giả, tóm tắt, mức ảnh hưởng, trạng thái; mở ra xem chi tiết. |

### 2.3 Đồng bộ tự động giữa agent
| Mã | Yêu cầu |
|---|---|
| FR-77 | Sự kiện và phản ứng tự động (không cần PMO chuyển lời): |
| | • Dev ghi đề xuất `BUG_FOUND`/`SOLUTION_CHANGE`/`SCOPE_CHANGE` → mở phiên **SA** để đánh giá và cập nhật `plan.md`, `analysis.md`, AC. |
| | • SA đổi `plan.md` hoặc AC (sau đề xuất, sau câu trả lời của PMO, hoặc sau khi PMO chốt lại) → mở phiên **Dev** để đọc đúng phần bị đổi và điều chỉnh code, test đang làm (chỉ khi ticket đã được chốt). |
| | • SA kiểm chứng (`verify.md`) thấy lệch phạm vi hoặc hồi quy → mở phiên **Dev** với danh sách lỗi. |
| | • PMO tự sửa code (phát hiện qua git diff của worktree) → mở phiên **SA** ghi nhận vào `changes.md` và kiểm AC còn đúng không. |
| | • PMO báo bug sau deploy → mở phiên **SA** cập nhật logic đúng vào `analysis.md` trước khi giao Dev. |
| FR-78 | **Ngữ cảnh chỉ gồm phần thay đổi**: phiên được kích hoạt chỉ nhận đề xuất hoặc diff của tài liệu, cùng các đoạn tài liệu và chữ ký code liên quan (theo `refs`, `location`, tối đa 2–3 tầng). Không đọc lại cả ticket, không đọc chat. Prompt tĩnh của từng vai dùng prompt caching như hiện có. |
| FR-79 | SA phân loại mức ảnh hưởng của mỗi thay đổi: `TECHNICAL_ONLY` thì SA tự áp dụng, Dev làm tiếp, PMO chỉ được báo trong room; `AC`, `BUSINESS_RULE`, `SCOPE` (kể cả thêm repo/service) thì đề xuất chuyển `NEEDS_PMO`, ticket quay về **Chờ PMO chốt**, Dev dừng, bật thông báo quan trọng (khay hệ thống nếu có). Phân loại có luật cứng hỗ trợ: đổi nội dung AC hoặc business rule trong `analysis.md`, hay thêm repo vào `plan.md`, luôn bị coi là cần PMO, bất kể agent tự đánh giá thế nào. |
| FR-80 | Mọi cập nhật tự động tạo version mới của tài liệu và một thẻ trong room: "SA đã cập nhật plan.md (v4) theo đề xuất của Dev", kèm diff trước/sau và nút "Hoàn tác" (khôi phục version trước). |
| FR-81 | **Chống vòng lặp**: cùng một đề xuất (theo `id` gốc), SA và Dev qua lại tối đa 2 vòng. Quá giới hạn thì dừng, đánh dấu `NEEDS_PMO`, trình bày hai quan điểm ngắn gọn cho PMO quyết. Mỗi ticket có trần số phiên tự kích hoạt mỗi giờ (mặc định 10), vượt thì tạm dừng và báo PMO. |
| FR-82 | Hàng đợi sự kiện theo ticket, xử lý tuần tự trong cùng ticket để hai phiên không cùng sửa một tài liệu; khác ticket thì chạy song song theo giới hạn chung. Sự kiện được lưu bền (khởi động lại DevMap vẫn xử lý tiếp). |
| FR-83 | Nút "Tạm dừng đồng bộ tự động" trên mỗi ticket; khi dừng, sự kiện vẫn được ghi lại và xử lý khi PMO bật lại. |
| FR-84 | `summary.md` được cập nhật sau mỗi lần đồng bộ để các phiên sau có ngữ cảnh đúng. |

### 2.4 Mô hình trạng thái mới
Sửa máy trạng thái ticket hiện có theo mô hình **3 lớp**. Trạng thái chỉ đổi ở các mốc có kiểm soát; các vòng trao đổi kỹ thuật giữa SA và Dev **không** làm đổi trạng thái.

| Lớp | Ý nghĩa | Ai thay đổi | Lưu ở |
|---|---|---|---|
| Trạng thái (`status`) | Giai đoạn của task | Luồng cố định và PMO | `task.md` |
| Hoạt động (`activity`) | Ngay lúc này ai đang làm gì | Agent, tự động | Bộ nhớ runtime + SSE, không ghi vào tài liệu |
| Cờ (`flags`) | Điều cần chú ý | Hệ thống | `task.md` |

| Mã | Yêu cầu |
|---|---|
| FR-85 | 8 trạng thái: `NEW` (Mới), `ANALYSIS` (Phân tích: SA làm chính, Dev chỉ đề xuất), `WAIT_APPROVAL` (Chờ PMO chốt), `READY` (Sẵn sàng, hàng đợi), `IMPLEMENT` (Triển khai: Dev làm chính, SA tự cập nhật tài liệu kỹ thuật), `VERIFY` (Kiểm chứng: SA đối chiếu diff với yêu cầu), `WAIT_CONFIRM` (Chờ PMO xác nhận push), `DONE`. Bỏ trạng thái "Mở lại" cũ, thay bằng hành động "Báo bug / Bổ sung logic". Viết migration chuyển ticket cũ: `REOPENED` → `ANALYSIS` kèm cờ `reopened`, trạng thái "SA đối chiếu" (nếu có) → `VERIFY`. |
| FR-86 | Bảng chuyển trạng thái (kiểm ở server, ngoài bảng thì từ chối): |

| Từ → Đến | Kích hoạt | Ghi chú |
|---|---|---|
| `NEW` → `ANALYSIS` | Tự động khi tạo task | |
| `ANALYSIS` → `WAIT_APPROVAL` | SA gửi giải pháp | |
| `WAIT_APPROVAL` → `READY` | **PMO chốt** (lần đầu) | |
| `WAIT_APPROVAL` → `IMPLEMENT` | **PMO chốt lại** khi task đã từng triển khai | Không xếp lại hàng đợi |
| `WAIT_APPROVAL` → `ANALYSIS` | **PMO yêu cầu phân tích lại** | |
| `READY` → `IMPLEMENT` | Đến lượt, hoặc PMO cho chạy song song | Pull nhánh nền trước |
| `IMPLEMENT` → `VERIFY` | Dev xong, quality gate pass | |
| `VERIFY` → `IMPLEMENT` | Tự động: SA thấy lệch phạm vi hoặc hồi quy | |
| `IMPLEMENT`/`VERIFY` → `ANALYSIS` | Tự động: thay đổi ảnh hưởng AC, business rule hoặc phạm vi (từ đề xuất của Dev hoặc từ PMO) | Dừng Dev an toàn trước |
| `VERIFY` → `WAIT_CONFIRM` | SA xác nhận đạt | |
| `WAIT_CONFIRM` → `IMPLEMENT` | **PMO yêu cầu sửa** | |
| `WAIT_CONFIRM` → `DONE` | **PMO xác nhận push** | Xoá ngữ cảnh phiên |
| `DONE` → `ANALYSIS` | **PMO báo bug / bổ sung logic** | Giữ mã cũ, cờ `reopened` +1 |

| Mã | Yêu cầu |
|---|---|
| FR-87 | Nhãn lần chốt: `WAIT_APPROVAL` hiện "chốt lần đầu" hoặc "chốt lại · v<n>" (n là version của plan.md) để phân biệt. |
| FR-88 | Cờ: `needs_pmo` (bất đồng sau 2 vòng, vượt trần phiên, câu hỏi chờ PMO; task giữ trạng thái nhưng dừng chờ), `open_proposals` (số đề xuất đang mở), `sync_paused`, `reopened` (số lần), `mid_change` (thay đổi giữa chừng khi đang triển khai), `file_overlap` (chạm cùng file với task khác đang mở, kèm mã task đó). |
| FR-89 | Danh sách task bên trái hiển thị mỗi dòng 3 phần: mã + tên + nhãn trạng thái; dòng hoạt động hiện tại (ví dụ "SA đang cập nhật plan.md theo đề xuất của Dev"); biểu tượng cờ, `needs_pmo` nổi bật nhất. |

### 2.5 Thay đổi giữa chừng khi đang `IMPLEMENT`
**Mọi câu của PMO về logic hoặc bug khi task đang `IMPLEMENT` được chuyển cho SA phân loại trước**, không chuyển thẳng cho Dev (trừ khi PMO gọi `@Dev` và nội dung chỉ là kỹ thuật).

**Case A – PMO thay đổi logic**

| Mã | Case | Trạng thái | Xử lý |
|---|---|---|---|
| FR-90 | **A1. Làm rõ kỹ thuật**, không đổi AC (ví dụ "dùng lại hàm format tiền có sẵn", "log thêm request id") | Giữ `IMPLEMENT` | SA bổ sung `plan.md` (version mới), mở phiên Dev chỉ với phần bổ sung. Room hiện thẻ "SA đã bổ sung plan.md theo yêu cầu của PMO, Dev tiếp tục". |
| FR-91 | **A2. Đổi nghiệp vụ, AC hoặc phạm vi** (ví dụ "hoàn tiền tối đa 50%", "thêm trường hợp trả góp") | `IMPLEMENT` → `ANALYSIS` → `WAIT_APPROVAL` → `IMPLEMENT` | (1) Dừng Dev an toàn (FR-94). (2) Bật cờ `mid_change`. (3) SA cập nhật `analysis.md` thành version mới. (4) SA so code đã viết trong worktree với yêu cầu mới, phân loại từng thay đổi thành **giữ nguyên / phải sửa / phải bỏ**, ghi vào `plan.md` mục "Ảnh hưởng tới code đang làm". (5) Chuyển `WAIT_APPROVAL` nhãn "chốt lại · v<n>", thẻ duyệt hiện: SA hiểu thay đổi thế nào, AC nào đổi, phần code bị ảnh hưởng. (6) PMO chốt → về thẳng `IMPLEMENT`; Dev chỉ nhận phần thay đổi và danh sách phải sửa/bỏ. |

Phân loại A1/A2 dùng luật cứng như FR-79: mọi thay đổi nội dung AC, business rule trong `analysis.md`, hoặc thêm repo/service vào `plan.md`, đều là A2.

**Case B – PMO báo bug**

| Mã | Bug thuộc về | Trạng thái task đang làm | Xử lý |
|---|---|---|---|
| FR-92 | **B1. Chính phần đang viết** (PMO chạy thử thấy sai) | Giữ `IMPLEMENT` | Ghi đề xuất `BUG_FOUND`, tác giả PMO. Nguyên nhân rõ → phiên Dev sửa; chưa rõ → phiên SA tìm trước rồi giao Dev. SA thêm case vào AC: nếu chỉ là case bị sót của AC hiện có thì xử lý như A1; nếu cho thấy AC sai thì như A2. |
| | **B2. Code cũ cùng khu vực, PMO muốn sửa luôn trong task này** | `IMPLEMENT` → `ANALYSIS` → `WAIT_APPROVAL` → `IMPLEMENT` | Là mở rộng phạm vi: xử lý như A2, SA thêm phần sửa bug vào plan. |
| | **B3. Thuộc một task khác đã `DONE`** | Không đổi | Mở lại task đó bằng "Báo bug" (→ `ANALYSIS`), chạy riêng. Nếu hai task chạm cùng file: bật cờ `file_overlap` ở cả hai và hỏi PMO làm task nào trước; task không được ưu tiên tạm dừng ở điểm an toàn. |
| | **B4. Không thuộc task nào** | Không đổi | Tạo task `BUG` mới với mô tả PMO vừa nhập. |

| Mã | Yêu cầu |
|---|---|
| FR-93 | DevMap tự đoán B1–B4 bằng cách so file, chữ ký method mà bug nhắc tới với diff của worktree hiện tại, `plan.md` của task đang làm và `changes.md` của các task đã Done. Không chắc chắn thì hiện câu hỏi một dòng với các nút: "Bug trong phần đang làm" / "Sửa luôn trong task này" / "Thuộc task khác" / "Tạo task mới". Việc đoán chạy cục bộ, không gọi LLM. |
| FR-94 | **Dừng Dev an toàn**: phiên Dev đang chạy được yêu cầu dừng sau thao tác sửa file hiện tại; DevMap commit WIP **cục bộ** trong worktree (message `WIP(<mã>): tạm dừng do <lý do>`), ghi hash vào `changes.md`, không push. Nếu phiên không dừng trong 60 giây thì huỷ phiên và vẫn commit WIP phần đã ghi xuống đĩa. Khi tiếp tục, Dev bắt đầu từ commit WIP này. |
| FR-95 | Mọi thay đổi giữa chừng tạo version mới của tài liệu và ghi vào `history.md` một dòng: thời điểm, nguồn (PMO/Dev/SA), case (A1, A2, B1–B4), version trước → sau. |
| FR-96 | Cấu hình `room.skip_reapproval_for_pmo_changes` (mặc định `false`). Khi `true`, case A2 và B2 do chính PMO yêu cầu, nếu không thêm repo/service mới ngoài `plan.md`, thì bỏ bước `WAIT_APPROVAL`: SA cập nhật xong, task về thẳng `IMPLEMENT`, room hiện thẻ "Đã áp dụng thay đổi của PMO, không chốt lại" kèm nút Hoàn tác. |

## 3. Ràng buộc kỹ thuật
- Tách module rõ ràng: `room` (định tuyến, hiển thị), `proposal` (schema, lưu trữ), `orchestration` (hàng đợi sự kiện, luật kích hoạt, chống vòng lặp), theo Controller → Service → Repository.
- Luật kích hoạt viết dạng bảng cấu hình (sự kiện → vai nhận → điều kiện → loại ngữ cảnh), không rải `if` khắp nơi.
- Dùng lại Agent runner, lớp lọc bí mật, version tài liệu, SSE, thông báo khay đang có; không tạo đường gọi LLM mới bỏ qua chúng.
- Cổng duyệt của PMO (chốt giải pháp, xác nhận push, đổi DB schema) không đổi và vẫn kiểm ở server.
- Ghi log token theo phiên, đánh dấu phiên do sự kiện kích hoạt, để PMO thấy chi phí của đồng bộ tự động.
- Giao diện: HTML/CSS/JS thuần, tiếng Việt mặc định.

## 4. Kiểm thử bắt buộc
Dùng dự án mẫu `samples/shop/`. Có thể giả lập phản hồi của agent bằng `AgentProvider` giả để test chạy ổn định, cộng thêm một lượt chạy với agent thật.

- [ ] **AC-31 Định tuyến**: "@Dev chạy lại test" → Dev; "quy tắc hoàn tiền khi có phí là gì?" → SA; câu mơ hồ → hiện nút chọn; không có lần gọi LLM nào cho việc định tuyến.
- [ ] **AC-32 Quyền theo trạng thái**: trước khi chốt, yêu cầu Dev sửa code bị từ chối ở server, Dev chỉ ghi đề xuất.
- [ ] **AC-33 Dev → SA (kỹ thuật)**: Dev ghi `BUG_FOUND` mức `TECHNICAL_ONLY` → SA tự cập nhật `plan.md` thành version mới, thẻ diff hiện trong room, Dev tiếp tục, PMO không bị chặn.
- [ ] **AC-34 Dev → SA (nghiệp vụ)**: đề xuất làm đổi AC → ticket về Chờ PMO chốt, Dev dừng, có thông báo quan trọng; kể cả khi agent tự đánh giá là kỹ thuật, luật cứng vẫn bắt.
- [ ] **AC-35 SA → Dev**: SA đổi một bước trong `plan.md` sau khi đã chốt → Dev nhận đúng phần bị đổi và điều chỉnh code; đầu vào của phiên Dev không chứa toàn bộ ticket hay chat.
- [ ] **AC-36 Chống vòng lặp**: giả lập SA và Dev bất đồng → dừng sau 2 vòng, trình bày cho PMO; trần phiên mỗi giờ hoạt động.
- [ ] **AC-37 Tuần tự trong ticket**: hai sự kiện cùng lúc trên một ticket được xử lý lần lượt, không ghi đè tài liệu của nhau; khởi động lại DevMap giữa chừng vẫn xử lý tiếp.
- [ ] **AC-38 Hoàn tác**: PMO bấm Hoàn tác trên thẻ cập nhật tự động → tài liệu về version trước, Dev được báo.
- [ ] **AC-39 Song song**: "@SA rà nghiệp vụ, @Dev dò log" mở hai phiên chạy cùng lúc, hai kết quả về cùng room.
- [ ] **AC-40 Bảng chuyển trạng thái**: mọi cặp trạng thái ngoài bảng FR-86 bị server từ chối; trao đổi kỹ thuật SA ↔ Dev nhiều vòng không làm đổi `status`, chỉ đổi `activity`.
- [ ] **AC-41 Migration**: ticket cũ ở "Mở lại" chuyển thành `ANALYSIS` có cờ `reopened`; không mất dữ liệu.
- [ ] **AC-42 Case A1**: đang `IMPLEMENT`, PMO nói "dùng lại hàm format tiền có sẵn" → giữ `IMPLEMENT`, plan.md lên version, Dev nhận đúng phần bổ sung.
- [ ] **AC-43 Case A2**: đang `IMPLEMENT` và Dev đã sửa 3 file, PMO nói "hoàn tiền tối đa 50%" → Dev dừng, có commit WIP cục bộ, không push; trạng thái qua `ANALYSIS` rồi `WAIT_APPROVAL` nhãn "chốt lại"; plan.md liệt kê đúng file giữ/sửa/bỏ; PMO chốt → về thẳng `IMPLEMENT` (không qua `READY`), Dev bắt đầu từ commit WIP.
- [ ] **AC-44 Case B1**: PMO báo sai số tiền ở hàm Dev vừa viết → giữ `IMPLEMENT`, có đề xuất `BUG_FOUND` tác giả PMO, AC có thêm case, Dev sửa.
- [ ] **AC-45 Case B2**: PMO báo bug ở code cũ cùng service và chọn "Sửa luôn trong task này" → đi như A2, plan.md có thêm phần sửa bug.
- [ ] **AC-46 Case B3**: bug thuộc task đã Done chạm cùng file với task đang làm → task cũ mở lại, cả hai có cờ `file_overlap`, PMO được hỏi làm task nào trước.
- [ ] **AC-47 Case B4**: bug không khớp task nào → tạo task `BUG` mới, task đang làm không đổi.
- [ ] **AC-48 Tự đoán case**: với 4 mô tả bug mẫu, DevMap đoán đúng B1–B4 hoặc hỏi lại khi không chắc; không có lần gọi LLM nào cho việc đoán.
- [ ] **AC-49 Dừng an toàn quá hạn**: phiên Dev giả lập không phản hồi → huỷ sau 60 giây, phần đã ghi đĩa vẫn được commit WIP.
- [ ] **AC-50 Bỏ chốt lại**: bật `room.skip_reapproval_for_pmo_changes`, case A2 do PMO yêu cầu không thêm repo mới → không qua `WAIT_APPROVAL`, có thẻ Hoàn tác; nếu thêm repo mới thì vẫn phải chốt lại.

## 5. Bàn giao
- Code, test, cập nhật `docs/USER_GUIDE.md` (mục "Room và đồng bộ giữa agent"), `docs/TEST_PLAN.md`.
- Viết `docs/CHANGE-ROOM.md` tóm tắt FR-71 → FR-96, AC-31 → AC-50, schema đề xuất, bảng luật kích hoạt, mô hình trạng thái mới (bảng chuyển trạng thái, cờ), các case thay đổi giữa chừng và cấu hình mới, để PMO cập nhật vào tài liệu SRS gốc.
- Commit nhỏ theo bước, prefix `ROOM:`. Không push.
- Báo cáo cuối: việc đã làm, kết quả test, AC đã đạt, token tiêu thụ trung bình của một lần đồng bộ tự động, rủi ro còn lại.
