package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Exercises distinct stations and real simulated readers, without a shared database. */
class ReaderMultiStationTest extends CardTestFixture {
  @Test
  void distinctStationsCanConnectDifferentCardsButNotTheSameCard() {
    String other = UUID.randomUUID().toString();
    String second = hub.newCard();

    assertTrue(hub.activity(other, alias).selectedOwnedByOther());
    assertFalse(hub.activity(other, second).selectedOwnedByOther());
    assertEquals("READER_IN_USE", assertThrows(Failure.class, () -> hub.connect(other, alias)).code);
    assertEquals(second, hub.connect(other, second).alias());
    assertEquals(alias, hub.status(TERMINAL).alias());
    assertEquals(second, hub.status(other).alias());
    assertTrue(hub.status(TERMINAL).present());
    assertTrue(hub.status(other).present());
    assertTrue(hub.activity(TERMINAL, second).selectedOwnedByOther());
    assertFalse(hub.activity(other, second).selectedOwnedByOther());

    hub.eject(other);
    assertTrue(hub.status(TERMINAL).present());
    assertFalse(hub.status(other).present());
    assertFalse(hub.activity(TERMINAL, second).selectedOwnedByOther());
  }

  @Test
  void busyCardDoesNotBlockConnectingOrUsingAnotherCard() throws Exception {
    String other = UUID.randomUUID().toString(), second = hub.newCard();
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<?> first = workers.submit(() -> hub.use(TERMINAL, card -> {
        started.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        return null;
      }));
      try {
        assertTrue(started.await(3, TimeUnit.SECONDS));
        Future<String> secondCard = workers.submit(() -> {
          hub.connect(other, second);
          return hub.use(other, card -> card.status(second).alias());
        });
        assertEquals(second, secondCard.get(3, TimeUnit.SECONDS));
        assertTrue(hub.activity(TERMINAL, second).operating());
        assertFalse(hub.activity(other, alias).operating());
      } finally { release.countDown(); }
      first.get(3, TimeUnit.SECONDS);
    }
  }

  @Test
  void simultaneousClaimsGrantADeviceToExactlyOneStation() throws Exception {
    String second = hub.newCard(), one = UUID.randomUUID().toString(), two = UUID.randomUUID().toString();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Callable<String> connectOne = () -> connectAfter(start, one, second);
      Callable<String> connectTwo = () -> connectAfter(start, two, second);
      Future<String> a = workers.submit(connectOne), b = workers.submit(connectTwo);
      start.countDown();
      var outcomes = java.util.List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
      assertEquals(1, outcomes.stream().filter("CONNECTED"::equals).count());
      assertEquals(1, outcomes.stream().filter("READER_IN_USE"::equals).count());
      assertTrue(hub.status(TERMINAL).present());
      assertNotEquals(hub.status(one).present(), hub.status(two).present());
    }
  }

  private String connectAfter(CountDownLatch start, String terminal, String reader) throws Exception {
    if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
    try { hub.connect(terminal, reader); return "CONNECTED"; }
    catch (Failure failure) { return failure.code; }
  }

  @Test
  void failedAuthenticationReleasesOnlyItsConnectionAfterExclusiveCheck() throws Exception {
    String terminal = UUID.randomUUID().toString(), rival = UUID.randomUUID().toString();
    String second = hub.newCard();
    CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<?> connecting = worker.submit(() -> hub.connect(terminal, second, (card, status) -> {
        checking.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        throw new Failure(403, "CARD_AUTH", "Xác thực thẻ không thành công!");
      }));
      try {
        assertTrue(checking.await(3, TimeUnit.SECONDS));
        assertTrue(hub.activity(terminal, second).operating());
        assertTrue(hub.activity(rival, second).selectedOwnedByOther());
        assertEquals("READER_IN_USE", assertThrows(Failure.class, () -> hub.connect(rival, second)).code);
        assertTrue(hub.status(TERMINAL).present());
      } finally { release.countDown(); }
      ExecutionException failure = assertThrows(ExecutionException.class, () -> connecting.get(3, TimeUnit.SECONDS));
      assertEquals("CARD_AUTH", ((Failure) failure.getCause()).code);
      assertFalse(hub.status(terminal).present());
      assertFalse(hub.activity(rival, second).selectedOwnedByOther());
      assertEquals(second, hub.connect(rival, second).alias());
      assertTrue(hub.status(TERMINAL).present());
    }
  }

  @Test
  void closingAnEmptyStationPreventsItsOwnConnectButNotAnotherStation() throws Exception {
    String closing = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
    String second = hub.newCard(), third = hub.newCard();
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<?> closure = worker.submit(() -> hub.closeStation(closing, () -> {
        started.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      }));
      try {
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertEquals("READER_BUSY", assertThrows(Failure.class, () -> hub.connect(closing, second)).code);
        assertEquals(third, hub.connect(other, third).alias());
      } finally { release.countDown(); }
      closure.get(3, TimeUnit.SECONDS);
      assertEquals(second, hub.connect(closing, second).alias());
    }
  }

  @Test
  void recoveryInspectionCannotDeadlockAConcurrentCardMutation() throws Exception {
    var profile = init();
    hub.status(TERMINAL);
    CountDownLatch inspecting = new CountDownLatch(1), proceed = new CountDownLatch(1);
    CountDownLatch mutationStarted = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<?> inspection = workers.submit(() -> hub.inspectTopup(TERMINAL, card -> {
        inspecting.countDown();
        try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        return hub.use(TERMINAL, current -> current.status(alias));
      }));
      try {
        assertTrue(inspecting.await(3, TimeUnit.SECONDS));
        Future<?> mutation = workers.submit(() -> {
          mutationStarted.countDown();
          hub.guardCardMutation(profile.cardId(), () -> {});
        });
        assertTrue(mutationStarted.await(3, TimeUnit.SECONDS));
        proceed.countDown();
        inspection.get(3, TimeUnit.SECONDS);
        mutation.get(3, TimeUnit.SECONDS);
      } finally { proceed.countDown(); }
    }
  }

  @Test
  void topupReservationOnlyLocksTheOwningCard() {
    String other = UUID.randomUUID().toString(), second = hub.newCard();
    hub.connect(other, second);
    hub.reserveTopup(TERMINAL, "fixture-card", 99);
    assertTrue(hub.activity(TERMINAL, second).topupReserved());
    assertFalse(hub.activity(other, alias).topupReserved());
    assertEquals("TOPUP_IN_PROGRESS", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
    hub.eject(other);
    assertTrue(hub.status(TERMINAL).present());
    hub.releaseTopup(99);
    hub.eject(TERMINAL);
  }
}
