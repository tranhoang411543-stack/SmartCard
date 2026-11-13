package vn.edu.smartcard.backend;

import java.nio.file.*;
import java.util.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Operator-only CLI: authenticates SMTP but never sends a message or transfers money. */
final class IntegrationProbe {
  static void run(LocalConfig config) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(config.get("smtp.host", ""));
    sender.setPort(Integer.parseInt(config.get("smtp.port", "587")));
    sender.setUsername(config.get("smtp.username", ""));
    sender.setPassword(config.get("smtp.password", ""));
    Properties props = sender.getJavaMailProperties();
    props.setProperty("mail.smtp.auth", "true");
    props.setProperty("mail.smtp.starttls.enable", "true");
    props.setProperty("mail.smtp.starttls.required", "true");
    props.setProperty("mail.smtp.connectiontimeout", "6000");
    props.setProperty("mail.smtp.timeout", "6000");
    try {
      sender.testConnection();
      System.out.println("SMTP_AUTH: PASS (no email sent)");
    } catch (Exception e) {
      System.out.println("SMTP_AUTH: UNVERIFIED (" + e.getClass().getSimpleName() + ")");
    }
    try {
      BankGateway bank = new BankGateway(config);
      String data = bank.qr(1000, bank.sepay().enabled() ? "NTCT00000000000001" : "TTMTEST CONFIG");
      byte[] bytes = Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
      Files.write(config.root().resolve("qr-connectivity.png"), bytes);
      System.out.println("VIETQR_IMAGE: PASS (no transfer performed)");
      System.out.println(
          "BANK_CONFIRMATION: "
              + (bank.confirmationReady()
                  ? "CONFIGURED, live callback still needs verification"
                  : "NOT_CONFIGURED"));
    } catch (Exception e) {
      System.out.println("VIETQR_IMAGE: UNVERIFIED (" + e.getClass().getSimpleName() + ")");
    }
  }
}
