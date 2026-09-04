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

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.BLOCK_NOT_FOUND;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.INTERNAL_ERROR;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StateOverrideMap;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcRequestException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameterOrBlockHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;

public class EthCall extends AbstractBlockParameterOrBlockHashMethod {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EthCall.class);
  private final TransactionSimulator transactionSimulator;
  private final LabelledMetric<Counter> gasUsedCounter;

  public EthCall(
      final BlockchainQueries blockchainQueries,
      final TransactionSimulator transactionSimulator,
      final MetricsSystem metricsSystem) {
    super(blockchainQueries);
    this.transactionSimulator = transactionSimulator;
    this.gasUsedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.RPC,
            "eth_call_gas_used_total",
            "Total gas used by eth_call requests",
            "status");
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_CALL.getMethodName();
  }

  @Override
  protected BlockParameterOrBlockHash blockParameterOrBlockHash(
      final JsonRpcRequestContext request) {
    try {
      return request.getRequiredParameter(1, BlockParameterOrBlockHash.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid block or block hash parameters (index 1)", RpcErrorType.INVALID_BLOCK_PARAMS, e);
    }
  }

  @Override
  protected Object resultByBlockHash(final JsonRpcRequestContext request, final Hash blockHash) {
    final BlockHeader header = blockchainQueries.get().getBlockHeaderByHash(blockHash).orElse(null);

    if (header == null) {
      return errorResponse(request, BLOCK_NOT_FOUND);
    }
    return resultByBlockHeader(request, header);
  }

  /**
   * T-092 (infinity): eth_call ที่ระบุ block เป็น "pending" ให้จำลองบน state ที่รวม tx ในคิวแล้ว
   * (แบบเดียวกับ geth) แทนที่จะตกไปใช้ latest ตามค่าเดิมของ Besu
   */
  @Override
  protected Object pendingResult(final JsonRpcRequestContext request) {
    // T-092 (infinity): ตอน chain head ขยับพอดี state ของ parent อาจถูกปลดไปแล้ว (Bonsai)
    // → ลองใหม่ไม่กี่ครั้ง ; ถ้ายังไม่ได้ค่อยถอยไป latest ; ถ้ายังไม่ได้อีกคืน error สะอาดๆ
    // (ห้ามปล่อย exception หลุดออกไป เพราะ Besu จะ log stack trace ทำให้ log/disk โต)
    RuntimeException last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return pendingResultInternal(request);
      } catch (final RuntimeException e) {
        last = e;
        // หน่วงสั้นๆ ให้ world state archive ตั้งหลักหลัง chain head ขยับ
        try {
          Thread.sleep(25L);
        } catch (final InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    try {
      return latestResult(request);
    } catch (final RuntimeException e) {
      LOG.debug("pending/latest simulation ล้มเหลวทั้งคู่", last);
      return new JsonRpcErrorResponse(request.getRequest().getId(), INTERNAL_ERROR);
    }
  }

  private Object pendingResultInternal(final JsonRpcRequestContext request) {
    final CallParameter callParams = CallParameterUtil.validateAndGetCallParams(request);
    final Optional<StateOverrideMap> maybeStateOverrides = getAddressStateOverrideMap(request);
    final var pendingHeader = transactionSimulator.simulatePendingBlockHeader();
    return transactionSimulator
        .processOnPending(
            callParams,
            maybeStateOverrides,
            callParams.getStrict().orElse(Boolean.FALSE)
                ? TransactionValidationParams.transactionSimulatorAllowFutureNonce()
                : TransactionValidationParams
                    .transactionSimulatorAllowExceedingBalanceAndFutureNonce(),
            OperationTracer.NO_TRACING,
            pendingHeader)
        .map(
            result -> {
              final long gasUsed = result.getGasEstimate();
              if (result.isSuccessful()) {
                gasUsedCounter.labels("success").inc(gasUsed);
                return (Object)
                    new JsonRpcSuccessResponse(
                        request.getRequest().getId(), result.getOutput().toString());
              }
              gasUsedCounter.labels("error").inc(gasUsed);
              return (Object) errorResponse(request, result);
            })
        .orElseGet(
            () -> {
              gasUsedCounter.labels("internal_error").inc(0);
              return errorResponse(request, INTERNAL_ERROR);
            });
  }

  @Override
  protected Object resultByBlockHeader(
      final JsonRpcRequestContext request, final BlockHeader header) {
    CallParameter callParams = CallParameterUtil.validateAndGetCallParams(request);
    Optional<StateOverrideMap> maybeStateOverrides = getAddressStateOverrideMap(request);
    // TODO implement for block overrides

    return transactionSimulator
        .process(
            callParams,
            maybeStateOverrides,
            buildTransactionValidationParams(header, callParams),
            OperationTracer.NO_TRACING,
            (mutableWorldState, transactionSimulatorResult) ->
                transactionSimulatorResult.map(
                    result -> {
                      long gasUsed = result.getGasEstimate();
                      if (result.isSuccessful()) {
                        gasUsedCounter.labels("success").inc(gasUsed);
                        return new JsonRpcSuccessResponse(
                            request.getRequest().getId(), result.getOutput().toString());
                      } else {
                        gasUsedCounter.labels("error").inc(gasUsed);
                        return errorResponse(request, result);
                      }
                    }),
            header)
        .orElseGet(
            () -> {
              gasUsedCounter.labels("internal_error").inc(0);
              return errorResponse(request, INTERNAL_ERROR);
            });
  }

  @VisibleForTesting
  protected Optional<StateOverrideMap> getAddressStateOverrideMap(
      final JsonRpcRequestContext request) {
    try {
      return request.getOptionalParameter(2, StateOverrideMap.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcRequestException(
          "Invalid account overrides parameter (index 2)", RpcErrorType.INVALID_CALL_PARAMS, e);
    }
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    return (JsonRpcResponse) handleParamTypes(requestContext);
  }

  private JsonRpcErrorResponse errorResponse(
      final JsonRpcRequestContext request, final TransactionSimulatorResult result) {
    final ValidationResult<TransactionInvalidReason> validationResult =
        result.getValidationResult();
    if (validationResult != null && !validationResult.isValid()) {
      return errorResponse(request, JsonRpcError.from(validationResult));
    } else {
      final TransactionProcessingResult resultTrx = result.result();
      if (resultTrx != null && resultTrx.getRevertReason().isPresent()) {

        return errorResponse(
            request,
            new JsonRpcError(
                RpcErrorType.REVERT_ERROR, resultTrx.getRevertReason().get().toHexString()));
      }
      return errorResponse(request, RpcErrorType.INTERNAL_ERROR);
    }
  }

  private JsonRpcErrorResponse errorResponse(
      final JsonRpcRequestContext request, final JsonRpcError jsonRpcError) {
    return new JsonRpcErrorResponse(request.getRequest().getId(), jsonRpcError);
  }

  private JsonRpcErrorResponse errorResponse(
      final JsonRpcRequestContext request, final RpcErrorType rpcErrorType) {
    return errorResponse(request, new JsonRpcError(rpcErrorType));
  }

  private TransactionValidationParams buildTransactionValidationParams(
      final BlockHeader header, final CallParameter callParams) {

    final boolean isAllowExceedingBalance;
    // if it is not set explicitly whether we want a strict check of the balance or not. this will
    // be decided according to the provided parameters
    if (callParams.getStrict().isEmpty()) {
      isAllowExceedingBalance = isAllowExceedingBalanceAutoSelection(header, callParams);

    } else {
      isAllowExceedingBalance = !callParams.getStrict().orElse(Boolean.FALSE);
    }
    return isAllowExceedingBalance
        ? TransactionValidationParams.transactionSimulatorAllowExceedingBalanceAndFutureNonce()
        : TransactionValidationParams.transactionSimulatorAllowFutureNonce();
  }

  private boolean isAllowExceedingBalanceAutoSelection(
      final BlockHeader header, final CallParameter callParams) {

    boolean isZeroGasPrice = callParams.getGasPrice().map(Wei.ZERO::equals).orElse(true);

    if (header.getBaseFee().isPresent()) {
      if (callParams.getBlobVersionedHashes().isPresent()
          && (callParams.getMaxFeePerBlobGas().isEmpty()
              || callParams.getMaxFeePerBlobGas().get().equals(Wei.ZERO))) {
        return true;
      }
      boolean isZeroMaxFeePerGas = callParams.getMaxFeePerGas().orElse(Wei.ZERO).equals(Wei.ZERO);
      boolean isZeroMaxPriorityFeePerGas =
          callParams.getMaxPriorityFeePerGas().orElse(Wei.ZERO).equals(Wei.ZERO);
      if (isZeroGasPrice && isZeroMaxFeePerGas && isZeroMaxPriorityFeePerGas) {
        // After 1559, when gas pricing is not provided, 0 is used and the balance is not
        // checked
        return true;
      } else {
        // After 1559, when gas price is provided, it is interpreted as both the max and
        // priority fee and the balance is checked
        return false;
      }
    }
    // Prior 1559, when gas price == 0 or is not provided the balance is not checked
    return isZeroGasPrice;
  }
}
