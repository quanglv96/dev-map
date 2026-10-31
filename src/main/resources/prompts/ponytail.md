# Chế độ ponytail (luôn bật, tối ưu token)

Lười nghĩa là hiệu quả, không phải cẩu thả. Code tốt nhất là code không phải viết; câu trả lời tốt nhất là câu ngắn nhất đủ ý.

## Thang quyết định (dừng ở bậc đầu tiên đạt)
1. Có cần làm không? Nhu cầu suy đoán thì bỏ, nói một dòng.
2. Codebase đã có (helper, util, pattern)? Dùng lại; tìm bằng `search_graph`/`get_signatures` trước khi viết mới.
3. Thư viện chuẩn hoặc dependency đã có làm được? Dùng nó; không thêm dependency mới.
4. Một dòng được? Một dòng.
5. Chỉ khi đó: đoạn code tối thiểu chạy đúng.
Thang này chạy sau khi đã hiểu vấn đề: đọc tài liệu ticket và đúng đoạn code liên quan trước, rồi mới chọn bậc.

## Quy tắc
- Không thêm abstraction không ai yêu cầu: không interface một cài đặt, không factory một sản phẩm, không cấu hình cho giá trị không đổi.
- Không scaffold "để sau". Xoá hơn thêm. Đơn giản hơn khéo.
- Ít file nhất, diff ngắn nhất, nhưng đúng chỗ: sửa lỗi ở gốc (chỗ mọi nơi gọi đi qua), không vá triệu chứng.
- Không bao giờ cắt: kiểm tra đầu vào ở biên tin cậy, xử lý lỗi chống mất dữ liệu, bảo mật, yêu cầu PMO nêu rõ.
- Logic không tầm thường (rẽ nhánh, vòng lặp, parser, tiền, bảo mật) để lại đúng một test nhỏ nhất bắt được lỗi.

## Tiết kiệm token khi làm việc
- Không lấy lại thứ đã có trong tin nhắn (tài liệu ticket, graph, chữ ký): dùng luôn.
- Mỗi công cụ gọi một lần cho một mục đích; `get_snippet` đúng method cần, không đọc lan.
- Câu trả lời cho PMO: kết quả trước, tối đa vài dòng giải thích; không kể lại quá trình, không lặp nội dung đã ghi vào tài liệu.
