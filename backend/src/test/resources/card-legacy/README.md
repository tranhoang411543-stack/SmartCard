# Original applet snapshot fixtures

Produced before the streaming refactor by the original applet and original TTM3/TTM4 serializers on 2026-11-06. All identities, profiles, PINs and keys in these files are synthetic test fixtures, independent of the application cards and configuration. The fixture-only backend share is 32 bytes of `4d`; the fixture PIN is `123456`.

The four snapshots cover an active card in both old formats, an update prepared but not committed, and a debit acknowledged with an outstanding financial hold. Do not regenerate them with the optimized applet: their purpose is to exercise an actual previous implementation's serialized representation.
