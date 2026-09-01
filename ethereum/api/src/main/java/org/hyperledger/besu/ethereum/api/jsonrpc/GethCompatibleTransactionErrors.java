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

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;

import java.util.Map;
import java.util.Optional;

/**
 * Answers eth_sendRawTransaction the way geth does.
 *
 * <p>geth reports nearly every rejection as -32000 and leaves the message to say which one it was,
 * so that is what callers written against geth match on. Besu gives each rejection its own code and
 * writes its own message, and a caller moving from one to the other silently stops recognising any
 * of them: it sees an unfamiliar code carrying unfamiliar words, and can no longer tell "the sender
 * cannot pay, top it up" from "the node is having a moment, try again".
 *
 * <p>Besu's codes are the better idea — one of them even collides with EIP-1474, where -32004 means
 * the method is not supported rather than the sender being short — but a chain moving off geth does
 * not get to redefine what its callers already understand. The messages here are the ones
 * go-ethereum emits, so existing matching keeps working.
 *
 * <p>Only the response is affected. {@link TransactionInvalidReason} is untouched, and it is what
 * the pool, the block creator and the metrics act on.
 */
public class GethCompatibleTransactionErrors {

  /** geth answers essentially every transaction rejection with this code. */
  private static final int GETH_ERROR_CODE = -32000;

  private static final Map<TransactionInvalidReason, String> GETH_MESSAGES =
      Map.ofEntries(
          Map.entry(
              TransactionInvalidReason.UPFRONT_COST_EXCEEDS_BALANCE,
              "insufficient funds for gas * price + value"),
          Map.entry(TransactionInvalidReason.NONCE_TOO_LOW, "nonce too low"),
          Map.entry(TransactionInvalidReason.NONCE_TOO_HIGH, "nonce too high"),
          Map.entry(TransactionInvalidReason.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER, "nonce too high"),
          Map.entry(
              TransactionInvalidReason.INTRINSIC_GAS_EXCEEDS_GAS_LIMIT, "intrinsic gas too low"),
          Map.entry(TransactionInvalidReason.TRANSACTION_ALREADY_KNOWN, "already known"),
          Map.entry(
              TransactionInvalidReason.TRANSACTION_REPLACEMENT_UNDERPRICED,
              "replacement transaction underpriced"),
          Map.entry(TransactionInvalidReason.TRANSACTION_PRICE_TOO_LOW, "transaction underpriced"),
          Map.entry(TransactionInvalidReason.GAS_PRICE_TOO_LOW, "transaction underpriced"),
          Map.entry(
              TransactionInvalidReason.GAS_PRICE_BELOW_CURRENT_BASE_FEE, "transaction underpriced"),
          Map.entry(
              TransactionInvalidReason.MAX_FEE_PER_GAS_BELOW_CURRENT_BASE_FEE,
              "transaction underpriced"),
          Map.entry(TransactionInvalidReason.EXCEEDS_BLOCK_GAS_LIMIT, "exceeds block gas limit"),
          Map.entry(
              TransactionInvalidReason.EXCEEDS_TRANSACTION_GAS_LIMIT, "exceeds block gas limit"),
          Map.entry(TransactionInvalidReason.INVALID_SIGNATURE, "invalid sender"),
          Map.entry(TransactionInvalidReason.WRONG_CHAIN_ID, "invalid sender"),
          Map.entry(
              TransactionInvalidReason.MAX_PRIORITY_FEE_PER_GAS_EXCEEDS_MAX_FEE_PER_GAS,
              "max priority fee per gas higher than max fee per gas"),
          Map.entry(
              TransactionInvalidReason.TX_FEECAP_EXCEEDED, "tx fee exceeds the configured cap"),
          Map.entry(TransactionInvalidReason.INITCODE_TOO_LARGE, "max initcode size exceeded"),
          Map.entry(
              TransactionInvalidReason.INVALID_TRANSACTION_FORMAT,
              "transaction type not supported"),
          Map.entry(TransactionInvalidReason.NONCE_OVERFLOW, "nonce has max value"),
          Map.entry(
              TransactionInvalidReason.UPFRONT_COST_EXCEEDS_UINT256,
              "insufficient funds for gas * price + value"));

  private GethCompatibleTransactionErrors() {}

  /**
   * The geth answer for a rejection, where there is one.
   *
   * <p>Reasons geth has no counterpart for, such as the pool being disabled or a plugin turning the
   * transaction away, are left alone: inventing a geth message for something geth never says would
   * be worse than answering in Besu's own words.
   *
   * @param reason why the transaction was rejected
   * @param detail what Besu had to say about it, kept as the data field so nothing is lost
   * @return the geth-shaped error, or empty to answer as Besu normally would
   */
  public static Optional<JsonRpcError> forReason(
      final TransactionInvalidReason reason, final String detail) {
    return Optional.ofNullable(GETH_MESSAGES.get(reason))
        .map(message -> new JsonRpcError(GETH_ERROR_CODE, message, detail));
  }
}
