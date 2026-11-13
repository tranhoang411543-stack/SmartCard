package vn.edu.smartcard.backend;

import java.util.Properties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class MailGateway {
  private final LocalConfig config;

  public MailGateway(LocalConfig config) {
    this.config = config;
  }

  public boolean ready() {
    return !config.get("smtp.host", "").isBlank() && !config.get("smtp.from", "").isBlank();
  }

  public void recovery(String address, String code) {
    sendCode(address, code, "TTM Căn tin - Đặt lại mật khẩu quản trị",
        "Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này.");
  }

  public void setupOtp(String address, String code) {
    sendCode(address, code, "TTM Căn tin - Khởi tạo tài khoản admin",
        "Nếu bạn không yêu cầu khởi tạo tài khoản admin, hãy bỏ qua email này.");
  }

  private void sendCode(String address, String code, String subject, String ignoreMessage) {
    if (!ready())
      throw new Failure(
          503,
          "EMAIL_UNAVAILABLE",
          "Chức năng gửi email hiện chưa sẵn sàng. Vui lòng liên hệ quản trị hệ thống.");
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(config.get("smtp.host", ""));
    sender.setPort(Integer.parseInt(config.get("smtp.port", "587")));
    sender.setUsername(config.get("smtp.username", ""));
    sender.setPassword(config.get("smtp.password", ""));
    Properties props = sender.getJavaMailProperties();
    props.setProperty("mail.smtp.auth", String.valueOf(!config.get("smtp.username", "").isBlank()));
    props.setProperty("mail.smtp.starttls.enable", "true");
    props.setProperty("mail.smtp.starttls.required", config.get("smtp.tls.required", "true"));
    props.setProperty("mail.smtp.connectiontimeout", "5000");
    props.setProperty("mail.smtp.timeout", "5000");
    props.setProperty("mail.smtp.writetimeout", "5000");
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(config.get("smtp.from", ""));
    message.setTo(address);
    message.setSubject(subject);
    message.setText(
        "Mã xác nhận của bạn: "
            + code
            + "\n"
            + "Mã có hiệu lực 10 phút và chỉ sử dụng một lần.\n"
            + ignoreMessage);
    try {
      sender.send(message);
    } catch (RuntimeException e) {
      throw new Failure(503, "EMAIL_UNAVAILABLE", "Chưa gửi được email. Vui lòng thử lại sau.");
    }
  }
}
