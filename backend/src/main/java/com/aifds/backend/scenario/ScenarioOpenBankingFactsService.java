package com.aifds.backend.scenario;

import com.aifds.backend.scenario.history.ScenarioOpenBankingRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioOpenBankingFactsService {
    private final ScenarioOpenBankingRepository repository;

    public ScenarioOpenBankingFactsService(ScenarioOpenBankingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ScenarioOpenBankingFacts snapshot(FinancialTransaction transaction) {
        return repository.snapshot(transaction);
    }
}
