/* T-092 (infinity): eth_pendingTransactions แบบ geth — คืนรายการ tx ที่ค้างในคิว */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionPendingResult;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class EthPendingTransactions implements JsonRpcMethod {
  private final TransactionPool transactionPool;

  public EthPendingTransactions(final TransactionPool transactionPool) {
    this.transactionPool = transactionPool;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_PENDING_TRANSACTIONS.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final List<TransactionPendingResult> out = new ArrayList<>();
    transactionPool.getPendingTransactions().stream()
        .map(PendingTransaction::getTransaction)
        .sorted(
            Comparator.comparing((org.hyperledger.besu.ethereum.core.Transaction t) ->
                    t.getSender().toHexString())
                .thenComparingLong(org.hyperledger.besu.ethereum.core.Transaction::getNonce))
        .forEach(t -> out.add(new TransactionPendingResult(t)));
    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), out);
  }
}
