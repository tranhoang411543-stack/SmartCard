package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.smartcard.shared.Api;

class TopupQrControllerTest {
  @Test
  void fullImageIsOptionalAndKeepsExistingTopupContracts() throws Exception {
    String terminal = "bc4ab15b-5dca-488b-b7c8-20407aceba90";
    var wallet = mock(WalletService.class);
    var topup = new Api.Topup(77, "NTCT07035200000077", "TTM000001", 10_000,
        "WAITING_BANK", "data:image/png;base64,fixture", "Fixture");
    var offer = new Api.TopupQr(topup, 240_000);
    when(wallet.createTopupQr(terminal, 10_000, false)).thenReturn(offer);
    when(wallet.createTopupQr(terminal, 10_000, true)).thenReturn(offer);
    when(wallet.createTopup(terminal, 10_000)).thenReturn(topup);
    var mvc = MockMvcBuilders.standaloneSetup(new ApiController(null, null, null, null, wallet, null)).build();

    for (String fullImage : new String[] {null, "true"}) {
      var request = post("/api/topups").param("includeExpiry", "true")
          .header("X-Terminal-ID", terminal).contentType("application/json")
          .content("{\"amount\":10000}");
      if (fullImage != null) request.param("fullImage", fullImage);
      var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
      assertEquals(offer, SecurityMaterial.JSON.readValue(response.getContentAsByteArray(), Api.TopupQr.class));
    }
    var response = mvc.perform(post("/api/topups").header("X-Terminal-ID", terminal)
            .contentType("application/json").content("{\"amount\":10000}"))
        .andExpect(status().isOk()).andReturn().getResponse();
    assertEquals(topup, SecurityMaterial.JSON.readValue(response.getContentAsByteArray(), Api.Topup.class));
    verify(wallet).createTopupQr(terminal, 10_000, false);
    verify(wallet).createTopupQr(terminal, 10_000, true);
    verify(wallet).createTopup(terminal, 10_000);
  }
}
