package vn.edu.smartcard.backend;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
class SePayWebhookController {
  private final WalletService wallet;
  SePayWebhookController(WalletService wallet) { this.wallet = wallet; }

  @PostMapping(value = "/webhook", consumes = "application/json")
  Map<String, Boolean> receive(HttpServletRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(65537);
    if (bytes.length > 65536) throw new Failure(413, "SEPAY_PAYLOAD", "Payload SePay vượt giới hạn.");
    wallet.sepayEvent(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), authorization);
    return Map.of("success", true);
  }
}

/** Requests forwarded by ngrok can only reach POST /webhook. */
@Component
class SePayIngressFilter extends OncePerRequestFilter {
  static final String TUNNEL_HOST = "ttm-sepay-webhook.internal";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String host = request.getHeader("Host");
    boolean tunnel =
        request.getHeader("X-Forwarded-For") != null
            || (host != null && host.split(":", 2)[0].equalsIgnoreCase(TUNNEL_HOST));
    if (tunnel && !(request.getMethod().equals("POST") && request.getRequestURI().equals("/webhook"))) {
      response.setStatus(404);
      response.setContentType("application/json");
      response.getWriter().write("{\"success\":false}");
      return;
    }
    if (request.getRequestURI().equals("/webhook") && request.getContentLengthLong() > 65536) {
      response.sendError(413);
      return;
    }
    chain.doFilter(request, response);
  }
}
