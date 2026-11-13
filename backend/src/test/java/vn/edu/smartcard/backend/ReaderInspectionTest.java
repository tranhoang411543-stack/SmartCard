package vn.edu.smartcard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vn.edu.smartcard.shared.Api;

/** Reader/card operations use the real jCardSim fixture; latches hold their boundaries. */
class ReaderInspectionTest extends CardTestFixture {
  @Test
  void statusInspectionDoesNotBlockTheButtonAndEjectWaitsForIt() throws Exception {
    init();
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    hub.lifecycle(id -> {
      reading.countDown();
      try {
        if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("inspection timed out");
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      return new Api.CardLifecycle(id, "ACTIVE", 0);
    });
    try (ExecutorService workers = Executors.newFixedThreadPool(3)) {
      Future<Api.ReaderStatus> status = workers.submit(() -> hub.status(TERMINAL));
      try {
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        assertFalse(hub.activity(TERMINAL).operating(), "A status inspection is not a payment or write");
        String other = UUID.randomUUID().toString();
        assertEquals("READER_IN_USE", assertThrows(Failure.class, () -> hub.eject(other)).code);
        Future<?> eject = workers.submit(() -> hub.eject(TERMINAL));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!hub.activity(TERMINAL).operating() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(hub.activity(TERMINAL).operating(), "A requested disconnect blocks new work");
        assertFalse(eject.isDone(), "A disconnect must wait until the status APDU is finished");
        Future<String> competing = workers.submit(() -> assertThrows(Failure.class,
            () -> hub.use(TERMINAL, c -> fail("New operation started while disconnect was waiting"))).code);
        assertEquals("READER_BUSY", competing.get(2, TimeUnit.SECONDS));
        Future<String> secondInspection = workers.submit(() -> assertThrows(Failure.class,
            () -> hub.status(TERMINAL)).code);
        assertEquals("READER_BUSY", secondInspection.get(2, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(status.get(5, TimeUnit.SECONDS).present());
        eject.get(5, TimeUnit.SECONDS);
        assertFalse(hub.activity(TERMINAL).connected());
      } finally { release.countDown(); }
    }
  }

  @Test
  void aTimedOutDisconnectDoesNotHappenLater() throws Exception {
    init();
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    hub.lifecycle(id -> {
      reading.countDown();
      try {
        if (!release.await(12, TimeUnit.SECONDS)) throw new AssertionError("inspection timed out");
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      return new Api.CardLifecycle(id, "ACTIVE", 0);
    });
    try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Future<Api.ReaderStatus> status = worker.submit(() -> hub.status(TERMINAL));
      try {
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        assertEquals("READER_BUSY", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
      } finally { release.countDown(); }
      assertTrue(status.get(5, TimeUnit.SECONDS).present());
      assertTrue(hub.activity(TERMINAL).connected(), "A timed-out request must not eject later");
      assertFalse(hub.activity(TERMINAL).operating());
    }
  }

  @Test
  void disconnectDuringBlockedStatusStillHonorsTopupReservation() throws Exception {
    var profile = init();
    hub.studentSession(TERMINAL);
    hub.reserveTopup(TERMINAL, profile.cardId(), 42);
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    hub.lifecycle(id -> {
      reading.countDown();
      try {
        if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("inspection timed out");
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
      return new Api.CardLifecycle(id, "BLOCKED", 0);
    });
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Api.ReaderStatus> status = workers.submit(() -> hub.status(TERMINAL));
      try {
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        Future<String> eject = workers.submit(() ->
            assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!hub.activity(TERMINAL).operating() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(hub.activity(TERMINAL).operating());
        release.countDown();
        ExecutionException inspection = assertThrows(ExecutionException.class,
            () -> status.get(5, TimeUnit.SECONDS));
        assertEquals("READER_BUSY", ((Failure) inspection.getCause()).code);
        assertEquals("TOPUP_IN_PROGRESS", eject.get(5, TimeUnit.SECONDS));
        assertTrue(hub.activity(TERMINAL).connected());
        assertTrue(hub.activity(TERMINAL).topupReserved());
      } finally { release.countDown(); }
    }
  }

  @Test
  void criticalOperationStillRejectsDisconnectImmediately() throws Exception {
    init();
    CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
      Future<?> operation = workers.submit(() -> hub.use(TERMINAL, card -> {
        running.countDown();
        try {
          if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("operation timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        return null;
      }));
      try {
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(hub.activity(TERMINAL).operating());
        assertEquals("READER_BUSY", assertThrows(Failure.class, () -> hub.eject(TERMINAL)).code);
      } finally { release.countDown(); }
      operation.get(5, TimeUnit.SECONDS);
      assertTrue(hub.status(TERMINAL).present());
    }
  }
}
