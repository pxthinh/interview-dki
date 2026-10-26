package pxt.dki.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Meeting Room Booking API",
                version = "1.0.0",
                description = """
                        REST API đặt phòng họp nội bộ: quản lý phòng, đặt/sửa/hủy booking, xem lịch, \
                        tìm phòng trống và đồng bộ Google Calendar theo phòng.

                        Đăng nhập qua POST /api/v1/auth/login rồi bấm Authorize, dán accessToken. \
                        Tài khoản mẫu: admin@dki.local / Admin@123 (ADMIN), alice@dki.local / User@123 (USER).

                        Quy tắc đặt phòng: lưới 15 phút, dài 15 phút đến 8 giờ, trong 07:00-20:00 \
                        giờ Việt Nam cùng một ngày, tối đa 90 ngày tới. Trùng lịch trả 409 kèm chi tiết \
                        booking đang chiếm chỗ.""",
                contact = @Contact(name = "DKI coding test")),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Dán accessToken nhận được từ /api/v1/auth/login")
public class OpenApiConfig {
}
