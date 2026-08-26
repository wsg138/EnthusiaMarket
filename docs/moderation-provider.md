# Staff Moderation Provider

EnthusiaMarket owns all stall, shop, blacklist, and ownership mutations. It publishes
`MarketModerationApi` version 1 as a Bukkit service so EnthusiaStaff can coordinate
case-linked moderation without reflecting into Market internals or writing Market tables.

## Service boundary

The public contract is under `net.enthusia.market.api.moderation`. Its records contain
only JDK types and remain safe to share through Paper's services manager. Consumers must
compile against the contract without shading a second copy into their runtime JAR.

The Bukkit service boundary assumes that installed server plugins are trusted runtime
code. EnthusiaStaff authenticates the operator and authorizes the case action before it
calls this provider; Market then validates the target, case, stall, checksum, revision,
and durable state transition. The API is not a sandbox against a malicious plugin in
the same JVM, because co-resident plugin code can inspect services, process memory, and
server configuration. Operators must therefore restrict plugin installation and update
access to trusted artifacts and administrators.

The provider is registered after migration and repository initialization and is
unregistered before shutdown cleanup. An absent provider, an API version other than 1,
or an unavailable database must block new moderation mutations. Existing durable state
remains available for later recovery.

## Operation lifecycle

| State | Meaning | Allowed operator action |
| --- | --- | --- |
| `PREPARED` | The original state is snapshotted, shops are frozen, and the stall/player are reserved. Ownership is unchanged. | Explicitly approve confiscation or release the preparation. |
| `MODERATION_HOLD` | A named reviewer approved the exact prepared checksum. Ownership is removed while the snapshot and reservation remain. | Restore using the exact held checksum. |
| `RESTORED` | Original ownership, members, timing, shop freeze flags, and provider blacklist state were restored; reservations were released. | None; exact retries replay the result. |
| `RELEASED` | A preparation was cancelled without removing ownership; original state and reservations were restored. | None; exact retries replay the result. |
| `QUARANTINED` | Snapshot integrity or current-state verification failed. The provider did not guess or overwrite newer state. | Investigate the journal and database before any manual recovery. |

Review deadlines create alerts; they never approve confiscation automatically.

## Safety invariants

- `prepare` locks the stall and its shop rows before the first snapshot read, creates the
  durable stall lock, advances the moderation revision, freezes shops, and applies the
  prepared acquisition restriction in one database transaction.
- The same operation ID with the complete original request is replay-safe. Changing its
  target, case, stall, review deadline, recovery window, or blacklist expiry is a conflict.
- Confiscation, release, and restoration verify the expected checksum and optimistic
  revision before changing state.
- A live stall reservation or player acquisition fence rejects buyouts, auction awards,
  transfers, and conflicting shop mutations before money, ownership, or items change.
- The process-local gate rejects ordinary purchase and auction work immediately; guarded
  repository writes remain the durable backstop and charged buyers are refunded if the
  reservation wins the final race.
- Stall lookup and snapshots are limited to 100 stalls or shops, and snapshots are
  limited to 1 MiB. Oversized state is rejected before a destructive transition.
- Provider work uses two workers and a bounded 64-request queue. Saturation fails the
  new request instead of allowing an unbounded moderation backlog.
- Terminal rows remain in the journal for audit. They are not deleted as part of normal
  completion.

## Persistence

Migration `V028__market_moderation_provider.sql` adds:

- `stalls.moderation_revision` for optimistic fencing;
- `market_moderation_operations` for versioned snapshots, checksums, reviewer identity,
  deadlines, recovery windows, requested blacklist expiry, revisions, and terminal audit
  state;
- `market_moderation_locks` for one active operation per stall;
- `market_player_fences` for acquisition serialization; and
- `market_stall_blacklists` for revisioned case-linked acquisition restrictions.

Existing migrations are immutable. Upstream v1.0.49 owns V025-V027; do not edit any
applied migration. Future schema changes must use a new forward migration after V028.

## Recovery procedure

1. Stop repeated moderation and acquisition attempts for the affected target or stall.
2. Record the operation ID, case ID, target UUID, stall ID, provider revision, state, and
   both checksums from the supported API or Staff journal.
3. If the operation is `PREPARED`, either approve it after human review or release it.
4. If it is `MODERATION_HOLD`, restore only when the current checksum still matches.
5. If it is `QUARANTINED`, preserve the lock and snapshot. Do not edit ownership,
   blacklist, or lock rows to make the operation appear complete.
6. After a restart, replay the same operation ID. Exact retries return the durable result;
   mismatched retries remain conflicts.

Representative destructive, latency, process-kill, and production-volume acceptance is
performed separately. Unit and MariaDB integration tests do not authorize live data use.

## Validation

Repository validation is self-contained: this provider uses only its ordinary repository
CI and disposable test databases. Consumer-specific runtime acceptance, deployment,
credentials, runners, or environment bridges belong outside this repository.

Run the complete build and the focused MariaDB provider test before release:

```bash
./gradlew --no-daemon clean test detekt shadowJar
./gradlew --no-daemon test \
  --tests net.badgersmc.em.infrastructure.moderation.JdbcMarketModerationMariaDbTest
```

Use a disposable MariaDB instance only. Confirm the shaded JAR contains the provider
contract exactly once and that the consuming Staff JAR contains no provider-owned API
classes. Release evidence must belong to the exact reviewed commit; stale, missing,
skipped, superseded, or different-revision runs are not substitutes.
