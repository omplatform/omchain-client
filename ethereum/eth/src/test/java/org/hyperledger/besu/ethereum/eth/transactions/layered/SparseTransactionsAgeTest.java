/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.transactions.layered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.eth.transactions.layered.AddReason.MOVE;
import static org.hyperledger.besu.ethereum.eth.transactions.layered.AddReason.NEW;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.transactions.BlobCache;
import org.hyperledger.besu.ethereum.eth.transactions.ImmutableTransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolMetrics;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

/**
 * Everything in the sparse layer sits behind a gap in its sender's nonce sequence and cannot be
 * mined until that gap is filled. Left alone it stays forever, and the sender's address stays
 * unusable. These tests cover dropping it once it has waited longer than the configured age.
 *
 * <p>Aged transactions are added with {@link AddReason#MOVE}, the reason used when a transaction
 * arrives from another layer, because that is the only reason that keeps the instance as-is. {@link
 * AddReason#NEW} stores a detached copy, whose age starts from the moment it was stored.
 */
public class SparseTransactionsAgeTest extends BaseTransactionPoolTest {

  private static final int MAX_AGE_SECONDS = 300;
  private static final long TEN_MINUTES_MS = 600_000L;
  private static final KeyPair OTHER_SENDER =
      SignatureAlgorithmFactory.getInstance().generateKeyPair();

  private SparseTransactions layerWithMaxAge(final int maxAgeSeconds) {
    final TransactionPoolConfiguration poolConfig =
        ImmutableTransactionPoolConfiguration.builder().maxFutureAgeSeconds(maxAgeSeconds).build();
    final TransactionPoolMetrics metrics = new TransactionPoolMetrics(new NoOpMetricsSystem());
    return new SparseTransactions(
        poolConfig,
        mock(EthScheduler.class),
        new EvictCollectorLayer(metrics),
        metrics,
        (a, b) -> false,
        new BlobCache());
  }

  /** A transaction, from the default sender, that entered the layer at the given moment. */
  private PendingTransaction stuckSince(final long nonce, final long addedAt) {
    return PendingTransaction.newPendingTransaction(
        createTransaction(nonce), false, false, (byte) 0, addedAt);
  }

  private PendingTransaction stuckSince(
      final long nonce, final long addedAt, final KeyPair sender) {
    return PendingTransaction.newPendingTransaction(
        createTransaction(nonce, sender), false, false, (byte) 0, addedAt);
  }

  private void onBlockAdded(final SparseTransactions layer) {
    layer.blockAdded(mock(FeeMarket.class), mock(BlockHeader.class), new HashMap<Address, Long>());
  }

  private static long millisAgo(final long millis) {
    return System.currentTimeMillis() - millis;
  }

  @Test
  public void dropsWholeSenderWaitingLongerThanMaxAge() {
    final SparseTransactions layer = layerWithMaxAge(MAX_AGE_SECONDS);
    layer.add(stuckSince(5, millisAgo(TEN_MINUTES_MS)), 5, MOVE);
    layer.add(stuckSince(6, millisAgo(TEN_MINUTES_MS)), 6, MOVE);
    assertThat(layer.count()).isEqualTo(2);

    onBlockAdded(layer);

    // both go, not only the oldest: dropping one would leave the other behind the same gap
    assertThat(layer.count()).isZero();
  }

  @Test
  public void dropsTheRestOfASenderEvenWhenOnlyItsOldestExceededMaxAge() {
    final SparseTransactions layer = layerWithMaxAge(MAX_AGE_SECONDS);
    layer.add(stuckSince(5, millisAgo(TEN_MINUTES_MS)), 5, MOVE);
    layer.add(stuckSince(6, System.currentTimeMillis()), 6, MOVE);

    onBlockAdded(layer);

    assertThat(layer.count()).isZero();
  }

  @Test
  public void keepsSenderStillWithinMaxAge() {
    final SparseTransactions layer = layerWithMaxAge(MAX_AGE_SECONDS);
    layer.add(stuckSince(5, millisAgo(TEN_MINUTES_MS / 10)), 5, MOVE);

    onBlockAdded(layer);

    assertThat(layer.count()).isEqualTo(1);
  }

  @Test
  public void keepsFreshlyArrivedTransaction() {
    final SparseTransactions layer = layerWithMaxAge(MAX_AGE_SECONDS);
    layer.add(stuckSince(5, millisAgo(TEN_MINUTES_MS)), 5, NEW);

    onBlockAdded(layer);

    // stored as a detached copy, so its age starts now regardless of the original timestamp
    assertThat(layer.count()).isEqualTo(1);
  }

  @Test
  public void leavesOtherSendersAlone() {
    final SparseTransactions layer = layerWithMaxAge(MAX_AGE_SECONDS);
    layer.add(stuckSince(5, millisAgo(TEN_MINUTES_MS)), 5, MOVE);
    layer.add(stuckSince(5, System.currentTimeMillis(), OTHER_SENDER), 5, MOVE);
    assertThat(layer.count()).isEqualTo(2);

    onBlockAdded(layer);

    assertThat(layer.count()).isEqualTo(1);
    assertThat(layer.getAll().get(0).getSender())
        .isEqualTo(Address.extract(OTHER_SENDER.getPublicKey()));
  }

  @Test
  public void keepsEverythingWhenDisabled() {
    final SparseTransactions layer = layerWithMaxAge(0);
    layer.add(stuckSince(5, millisAgo(TEN_MINUTES_MS)), 5, MOVE);

    onBlockAdded(layer);

    assertThat(layer.count()).isEqualTo(1);
  }
}
