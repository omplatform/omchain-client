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
package org.hyperledger.besu.ethereum.api.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The wording here is what go-ethereum emits, and callers written against geth match on it. Change
 * a string in this test only alongside evidence of what geth actually says, otherwise the callers
 * this exists for stop recognising the answer.
 */
public class GethCompatibleTransactionErrorsTest {

  private static final int GETH_CODE = -32000;

  static Stream<Arguments> gethWording() {
    return Stream.of(
        Arguments.of(
            TransactionInvalidReason.UPFRONT_COST_EXCEEDS_BALANCE,
            "insufficient funds for gas * price + value"),
        Arguments.of(TransactionInvalidReason.NONCE_TOO_LOW, "nonce too low"),
        Arguments.of(TransactionInvalidReason.NONCE_TOO_HIGH, "nonce too high"),
        Arguments.of(
            TransactionInvalidReason.INTRINSIC_GAS_EXCEEDS_GAS_LIMIT, "intrinsic gas too low"),
        Arguments.of(TransactionInvalidReason.TRANSACTION_ALREADY_KNOWN, "already known"),
        Arguments.of(
            TransactionInvalidReason.TRANSACTION_REPLACEMENT_UNDERPRICED,
            "replacement transaction underpriced"),
        Arguments.of(TransactionInvalidReason.GAS_PRICE_TOO_LOW, "transaction underpriced"),
        Arguments.of(TransactionInvalidReason.EXCEEDS_BLOCK_GAS_LIMIT, "exceeds block gas limit"));
  }

  @ParameterizedTest(name = "{0} answers \"{1}\"")
  @MethodSource("gethWording")
  public void answersWithGethWordingAndCode(
      final TransactionInvalidReason reason, final String expected) {
    final var error = GethCompatibleTransactionErrors.forReason(reason, "besu said something");

    assertThat(error).isPresent();
    assertThat(error.get().getCode()).isEqualTo(GETH_CODE);
    assertThat(error.get().getMessage()).isEqualTo(expected);
  }

  @Test
  public void keepsWhatBesuSaidAsTheDetail() {
    final var error =
        GethCompatibleTransactionErrors.forReason(
            TransactionInvalidReason.UPFRONT_COST_EXCEEDS_BALANCE,
            "transaction up-front cost 0x2 exceeds the 0x1 the sender has left");

    assertThat(error).isPresent();
    assertThat(error.get().getData())
        .isEqualTo("transaction up-front cost 0x2 exceeds the 0x1 the sender has left");
  }

  @Test
  public void leavesReasonsGethHasNoWordFor() {
    // geth has no counterpart for these, and inventing one would be worse than answering as Besu
    Stream.of(
            TransactionInvalidReason.TX_POOL_DISABLED,
            TransactionInvalidReason.PLUGIN_TX_POOL_VALIDATOR,
            TransactionInvalidReason.CHAIN_HEAD_WORLD_STATE_NOT_AVAILABLE)
        .forEach(
            reason ->
                assertThat(GethCompatibleTransactionErrors.forReason(reason, "detail")).isEmpty());
  }
}
