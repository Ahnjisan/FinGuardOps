package com.aifds.backend.scenario;

import com.aifds.backend.scenario.history.ScenarioFundFlowRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioFundFlowFactsService {
    private final ScenarioFundFlowRepository repository;

    public ScenarioFundFlowFactsService(ScenarioFundFlowRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ScenarioFundFlowFacts snapshot(FinancialTransaction transaction) {
        return repository.snapshot(transaction);
    }
}
