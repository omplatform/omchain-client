# omchain-client

A fork of [Hyperledger Besu](https://github.com/hyperledger/besu) for the omchain
network (chainId 1246).

Base: **26.8.1** · branch `omclient-2681` · version string `26.8.1-omchain`

## Why this fork exists

omchain is a private PoA chain that has been running since 2022 and carries real
customer balances. Two things it needs are not in upstream Besu:

1. **geth-compatible pending state.** The custodial wallet queues transfers and
   sizes each one against the balance at the `pending` tag. Upstream resolves
   `pending` to the chain head, so the wallet would ignore what it has already
   sent and overdraw the account.
2. **A path from Clique to QBFT.** Upstream can schedule consensus transitions
   only within one family. Starting a new chain is not an option for a chain that
   already holds years of history.

Everything else we need, upstream provides. The fork is deliberately small so it
stays cheap to rebase.

## What is patched

| Commit | Area | Why |
|---|---|---|
| `feat(rpc)` | `eth_getBalance`, `eth_call`, `TransactionSimulator`, `eth_pendingTransactions` | see reason 1 above |
| `feat(consensus)` | Clique → QBFT migration | see reason 2 above |
| `build` | version stamp | an unset version is written into the database and then blocks starting on any other build |

Three commits, thirteen files. Nothing else is touched.

## What upstream already handles (do not re-add)

Earlier versions of this fork carried patches that are no longer needed:

- `txpool_content`, `txpool_contentFrom`, `txpool_inspect`, `txpool_status` —
  upstream ships these as of 26.5.
- The `newHeads` websocket subscription silently delivering nothing — upstream
  now uses `JsonRpcObjectMapperFactory` instead of `Json.encode`.
- Refusing to seal without peers, and dropping a self-mined block when the chain
  head moved. Both guarded Clique sealing, which upstream removed in 26.8 along
  with the rest of the PoW mining path. Under QBFT a lone node cannot finalise a
  block on its own, so the hazard is gone.

## Building

The build runs in a container; no JDK is needed on the host.

```bash
docker volume create omchain-gradle-cache
docker run --rm -v "$PWD:/src" -w /src \
  -v omchain-gradle-cache:/root/.gradle \
  eclipse-temurin:25-jdk ./gradlew --no-daemon installDist -x test

docker build -t omchain-client:26.8.1 -f - build/install/ <<'EOF'
FROM eclipse-temurin:25-jre
COPY besu /opt/besu
RUN chmod +x /opt/besu/bin/besu
ENTRYPOINT ["/opt/besu/bin/besu"]
EOF
```

The distribution task lives on the root project, not on `:app`.

## Running

Besu 26.8 **cannot seal Clique blocks** — the Clique block creator was removed
with the PoW mining path. It can still validate Clique history, so it will follow
a Clique chain but never produce on one. A chain must reach QBFT before its
validators move to 26.8. Use a 25.9-based build to carry the chain through the
Clique → QBFT transition, then upgrade.

Flags that 25.9 accepted and 26.8 rejects:

```
--miner-enabled                                       removed with PoW mining
--miner-coinbase                                      removed with PoW mining
--Xeth65-tx-announced-buffering-period-milliseconds   removed; the 500ms default
                                                      remains and measured no
                                                      difference to confirmation
                                                      time on our chain
```

`--rpc-http-api=CLIQUE` is also rejected.

A database written by a differently-versioned build needs
`--version-compatibility-protection=false` once, which restamps it.

## Verification

Run against a 46.6M block copy of production, migrated to QBFT with Shanghai and
Cancun active:

```
14 passed · 0 failed · 1 skipped
block time            1–2s under load, 30s when idle (empty-block suppression)
confirmation          median 1.02s
throughput            101,055 tx in 45s (2,246/s), up to 2,862 tx per block
pending state         balance, nonce and eth_call all reflect the queue
history               block hashes and state roots unchanged across the upgrade
```

The one skip needs an unpatched Besu alongside for comparison, which the test
chain does not run.

**When measuring, generate transactions.** With empty-block suppression a healthy
idle chain produces nothing for 30s at a time, and a test that watches the block
number will call that a halt. Judge liveness by whether transactions confirm.
