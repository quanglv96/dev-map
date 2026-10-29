package com.devmap.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestCleanerTest {

  final RequestCleaner cleaner = new RequestCleaner();

  @Test
  void dropsPoliteFillerAndSplitsConstraints() {
    RequestCleaner.Cleaned c = cleaner.clean("""
        Chào em, anh nhờ em thêm hoàn tiền một phần cho đơn hàng nhé.
        Số tiền hoàn không được vượt quá số đã thanh toán.
        Cảm ơn em nhiều!""");
    assertThat(c.summary()).isEqualTo("Thêm hoàn tiền một phần cho đơn hàng.");
    assertThat(c.constraints()).containsExactly("Số tiền hoàn không được vượt quá số đã thanh toán.");
  }

  @Test
  void findsModulesByLooseName() {
    List<String> names = List.of("order-service", "payment-service", "notification-service");
    assertThat(cleaner.mentioned("Sửa Order Service và payment_service", names))
        .containsExactly("order-service", "payment-service");
    assertThat(cleaner.mentioned("reorder-services", names)).isEmpty();
  }

  @Test
  void slugFoldsVietnamese() {
    assertThat(RequestCleaner.slug("Đăng nhập bằng Google!")).isEqualTo("dang-nhap-bang-google");
    assertThat(RequestCleaner.slug("!!!")).isEqualTo("task");
  }
}
