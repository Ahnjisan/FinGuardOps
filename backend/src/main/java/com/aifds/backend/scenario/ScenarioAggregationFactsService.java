package com.aifds.backend.scenario;

import com.aifds.backend.scenario.history.ScenarioTransferWindowRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ScenarioAggregationFactsService {
    private final ScenarioTransferWindowRepository windows;

    public ScenarioAggregationFactsService(ScenarioTransferWindowRepository windows) {
        this.windows = windows;
    }

    @Transactional(readOnly = true)
    public ScenarioAggregationFacts snapshot(FinancialTransaction transaction) {
        FinancialTransaction tx = Objects.requireNonNull(transaction);
        if (tx.getTransactionType() != TransactionType.ACCOUNT_TRANSFER
                && tx.getTransactionType() != TransactionType.OPEN_BANKING_TRANSFER) {
            return null;
        }
        return new ScenarioAggregationFacts("SCN004-005-contract-v1",
                windows.outbound(tx), windows.inbound(tx));
    }
}
