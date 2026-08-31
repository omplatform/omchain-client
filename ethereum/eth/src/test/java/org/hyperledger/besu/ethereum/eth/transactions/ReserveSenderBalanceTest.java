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
package org.hyperledger.besu.ethereum.eth.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionPool.checkAgainstBalanceLeftForTransaction;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The stateless validator compares every transaction against the full confirmed balance on its own,
 * so a sender can be admitted more transactions than it can pay for. These tests cover the extra
 * check that accounts for what the sender already has queued.
 */
public class ReserveSenderBalanceTest {

  private static final KeyPair SENDER = SignatureAlgorithmFactory.getInstance().generateKeyPair();
  private static final long GAS_LIMIT = 21_000L;
  private static final Wei GAS_PRICE = Wei.of(1_000L);

  /** What one transaction of these tests can spend at most. */
  private static final Wei COST_PER_TX = Wei.of(GAS_LIMIT * 1_000L);

  /** Nothing here carries blobs, so the whole cost is gas limit times price plus value. */
  private static Wei upfrontCost(final Transaction transaction) {
    return transaction.getUpfrontCost(0L);
  }

  private Transaction transaction(final long nonce) {
    return new TransactionTestFixture()
        .nonce(nonce)
        .gasLimit(GAS_LIMIT)
        .gasPrice(GAS_PRICE)
        .value(Wei.ZERO)
        .createTransaction(SENDER);
  }

  private PendingTransaction queued(final long nonce) {
    return PendingTransaction.newPendingTransaction(transaction(nonce), false, false, (byte) 0);
  }

  private static Wei times(final int n) {
    return COST_PER_TX.multiply(n);
  }

  private boolean accepts(
      final Transaction incoming, final Wei balance, final PendingTransaction... queued) {
    return checkAgainstBalanceLeftForTransaction(
            incoming, balance, 0L, List.of(queued), ReserveSenderBalanceTest::upfrontCost)
        .isValid();
  }

  @Test
  public void acceptsWhatIsLeftAfterTheQueue() {
    // room for 3, two are queued, so the third fits
    assertThat(accepts(transaction(2), times(3), queued(0), queued(1))).isTrue();
  }

  @Test
  public void rejectsWhatTheQueueHasAlreadySpent() {
    // room for 3, three are queued, so the fourth does not
    assertThat(accepts(transaction(3), times(3), queued(0), queued(1), queued(2))).isFalse();
  }

  @Test
  public void saysWhatIsLeftAndHowManyAreAheadOfIt() {
    final var result =
        checkAgainstBalanceLeftForTransaction(
            transaction(3),
            times(3),
            0L,
            List.of(queued(0), queued(1), queued(2)),
            ReserveSenderBalanceTest::upfrontCost);

    assertThat(result.getInvalidReason())
        .isEqualTo(TransactionInvalidReason.UPFRONT_COST_EXCEEDS_BALANCE);
    assertThat(result.getErrorMessage())
        .contains("3 transaction(s) already in the pool")
        .contains("0x0"); // nothing left of the balance
  }

  @Test
  public void ignoresTransactionsThatRunAfterThisOne() {
    // only nonce 0 runs before nonce 1; nonces 2 and 3 are behind it and reserve nothing
    assertThat(accepts(transaction(1), times(2), queued(0), queued(2), queued(3))).isTrue();
  }

  @Test
  public void doesNotChargeTwiceForTheTransactionBeingReplaced() {
    // the queued nonce 1 is the one being replaced, so its cost is not owed on top
    assertThat(accepts(transaction(1), times(2), queued(0), queued(1))).isTrue();
  }

  @Test
  public void ignoresTransactionsTheChainHasAlreadyConfirmed() {
    // sender nonce moved to 2, so the queued 0 and 1 are on their way out and reserve nothing
    assertThat(
            checkAgainstBalanceLeftForTransaction(
                    transaction(3),
                    COST_PER_TX,
                    2L,
                    List.of(queued(0), queued(1)),
                    ReserveSenderBalanceTest::upfrontCost)
                .isValid())
        .isTrue();
  }

  @Test
  public void leavesTheFirstTransactionOfASenderToTheValidator() {
    // nothing queued ahead of it: the check must not second-guess the validator, even at zero
    assertThat(accepts(transaction(0), Wei.ZERO)).isTrue();
  }

  @Test
  public void keepsRejectingOnceTheQueueIsBeyondWhatTheSenderCanPay() {
    // room for 1 but three are queued: everything after them is unaffordable
    assertThat(accepts(transaction(9), COST_PER_TX, queued(0), queued(1), queued(2))).isFalse();
  }
}
