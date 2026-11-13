package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.smartcard.shared.Api;

class ReaderConnectAuthenticationTest extends DatabaseFixture {
  private MockMvc controller() {
    return MockMvcBuilders.standaloneSetup(new ApiController(auth, hub, cards, repo, wallet, bank))
        .setControllerAdvice(new ApiErrors()).build();
  }

  private org.springframework.test.web.servlet.ResultActions connect(
      MockMvc mvc, String terminal, String alias) throws Exception {
    return mvc.perform(post("/api/reader/connect").header("X-Terminal-ID", terminal)
        .contentType(MediaType.APPLICATION_JSON)
        .content(SecurityMaterial.JSON.writeValueAsBytes(new Api.Connect(alias, ""))));
  }

  @Test
  void registeredCardIsAuthenticatedBeforeConnectReturnsWithoutUsingPin() throws Exception {
    var profile = enroll("AT940101", "940000000001", 2026);
    String alias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    int attempts = repo.db.queryForObject("SELECT count(*) FROM pin_attempts WHERE card_id=?",
        Integer.class, profile.cardId());
    MockMvc mvc = controller();
    for (int n = 0; n < 2; n++) {
      var response = connect(mvc, TERMINAL, alias).andExpect(status().isOk())
          .andReturn().getResponse();
      Api.ReaderStatus result = SecurityMaterial.JSON.readValue(response.getContentAsByteArray(),
          Api.ReaderStatus.class);
      assertTrue(result.present());
      assertEquals(profile.cardId(), result.cardId());
      assertEquals("NONE", result.role());
      assertEquals(attempts, repo.db.queryForObject("SELECT count(*) FROM pin_attempts WHERE card_id=?",
          Integer.class, profile.cardId()));
      assertTrue(cards.prepareStudent(TERMINAL).present());
      hub.eject(TERMINAL);
    }
  }

  @Test
  void invalidEccDisconnectsOnlyThatCardAndDoesNotCountAsWrongPin() throws Exception {
    var first = enroll("AT940102", "940000000002", 2026);
    String firstAlias = hub.status(TERMINAL).alias();
    var second = enroll("AT940103", "940000000003", 2026);
    String secondAlias = hub.status(TERMINAL).alias();
    hub.eject(TERMINAL);
    MockMvc mvc = controller();
    connect(mvc, TERMINAL, firstAlias).andExpect(status().isOk());
    String other = UUID.randomUUID().toString();
    wallet.openStation(new Api.OpenStation(UUID.randomUUID().toString(), other));
    var invoice = wallet.createInvoice(new Api.CreateInvoice(List.of(new Api.OrderItem(1, 1))), other);
    String original = repo.find(second.cardId()).publicKey();
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    String wrong = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    repo.db.update("UPDATE cards SET public_key=? WHERE card_id=?", wrong, second.cardId());
    try {
      var response = connect(mvc, other, secondAlias).andExpect(status().isForbidden())
          .andReturn().getResponse();
      var error = SecurityMaterial.JSON.readTree(response.getContentAsByteArray());
      assertEquals("CARD_AUTH", error.path("code").asText());
      assertEquals("Xác thực thẻ không thành công!", error.path("message").asText());
      assertFalse(hub.status(other).present());
      assertFalse(hub.activity(TERMINAL, secondAlias).selectedOwnedByOther());
      assertEquals(first.cardId(), hub.status(TERMINAL).cardId());
      assertEquals(0, repo.lifecycle(second.cardId()).pinFailures());
      assertEquals(0, repo.db.queryForObject("SELECT count(*) FROM pin_attempts WHERE card_id=?",
          Integer.class, second.cardId()));
      assertEquals("OPEN", wallet.invoice(invoice.id()).status());
      assertEquals(invoice.total(), wallet.invoice(invoice.id()).total());
    } finally {
      repo.db.update("UPDATE cards SET public_key=? WHERE card_id=?", original, second.cardId());
    }
    try {
      connect(mvc, other, secondAlias).andExpect(status().isOk());
      assertEquals(second.cardId(), hub.status(other).cardId());
      assertEquals(first.cardId(), hub.status(TERMINAL).cardId());
    } finally {
      hub.eject(other);
    }
  }

  @Test
  void newUninitializedCardRemainsAvailableForAdminEnrollment() throws Exception {
    String alias = hub.newCard();
    var response = connect(controller(), TERMINAL, alias).andExpect(status().isOk())
        .andReturn().getResponse();
    Api.ReaderStatus result = SecurityMaterial.JSON.readValue(response.getContentAsByteArray(),
        Api.ReaderStatus.class);
    assertTrue(result.present());
    assertFalse(result.initialized());
    var enrolled = cards.initialize(TERMINAL,
        new Api.Enrollment(profile("AT940104", "940000000004", 2026), "123456"), admin().id());
    assertEquals(enrolled.cardId(), hub.status(TERMINAL).cardId());
  }
}
