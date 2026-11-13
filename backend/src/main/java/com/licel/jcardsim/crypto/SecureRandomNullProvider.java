package com.licel.jcardsim.crypto;

/**
 * Compatibility replacement for the unseeded RNG in the bundled 3.0.6.0 fork. Keep this binary
 * name: KeyPairImpl instantiates it internally. This class is host-only and is never compiled into
 * the Java Card applet. The JDK provider supplies OS entropy; generated key pairs are not
 * snapshots.
 */
public final class SecureRandomNullProvider extends java.security.SecureRandom {
  private static final long serialVersionUID = 1L;

  public SecureRandomNullProvider() {
    super();
  }
}
