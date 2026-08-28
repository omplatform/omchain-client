/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameterOrBlockHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.Quantity;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;

import java.util.function.Supplier;

public class EthGetBalance extends AbstractBlockParameterOrBlockHashMethod {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EthGetBalance.class);

  // T-092 (infinity): ใช้สำหรับอ่านยอดแบบ pending (Besu เดิมตอบเท่ากับ latest)
  private final org.hyperledger.besu.ethereum.transaction.TransactionSimulator transactionSimulator;

  public EthGetBalance(final BlockchainQueries blockchainQueries) {
    this(blockchainQueries, null);
  }

  public EthGetBalance(
      final BlockchainQueries blockchainQueries,
      final org.hyperledger.besu.ethereum.transaction.TransactionSimulator transactionSimulator) {
    super(blockchainQueries);
    this.transactionSimulator = transactionSimulator;
  }

  /**
   * T-092 (infinity): ยอดแบบ pending = ยอดล่าสุด + ผลของ tx ที่ยังค้างในคิว (พฤติกรรมแบบ geth)
   * ถ้าอ่านไม่ได้ด้วยเหตุใดก็ตาม ถอยไปใช้ latest แบบเดิม — ห้ามปล่อย exception หลุด
   */
  @Override
  protected Object pendingResult(final JsonRpcRequestContext request) {
    if (transactionSimulator == null) {
      return super.pendingResult(request);
    }
    final Address address;
    try {
      address = request.getRequiredParameter(0, Address.class);
    } catch (final JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid address parameter (index 0)", RpcErrorType.INVALID_ADDRESS_PARAMS, e);
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        final var balance = transactionSimulator.pendingAccountBalance(address);
        if (balance.isPresent()) {
          return Quantity.create(balance.get());
        }
      } catch (final RuntimeException e) {
        LOG.debug("pending balance ล้มเหลว (ครั้งที่ {})", attempt + 1, e);
      }
      try {
        Thread.sleep(25L);
      } catch (final InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    try {
      return super.pendingResult(request);
    } catch (final RuntimeException e) {
      LOG.debug("latest balance ล้มเหลวด้วย", e);
      return null;
    }
  }

  public EthGetBalance(final Supplier<BlockchainQueries> blockchainQueries) {
    super(blockchainQueries);
    this.transactionSimulator = null;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_GET_BALANCE.getMethodName();
  }

  @Override
  protected BlockParameterOrBlockHash blockParameterOrBlockHash(
      final JsonRpcRequestContext request) {
    return blockParameterOrBlockHashWithLatestDefault(request, 1);
  }

  @Override
  protected String resultByBlockHash(final JsonRpcRequestContext request, final Hash blockHash) {
    final Address address;
    try {
      address = request.getRequiredParameter(0, Address.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid address parameter (index 0)", RpcErrorType.INVALID_ADDRESS_PARAMS, e);
    }
    return blockchainQueries
        .get()
        .accountBalance(address, blockHash)
        .map(Quantity::create)
        .orElse(null);
  }
}
