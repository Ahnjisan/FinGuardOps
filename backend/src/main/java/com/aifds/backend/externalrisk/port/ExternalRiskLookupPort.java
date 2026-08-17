package com.aifds.backend.externalrisk.port;

import com.aifds.backend.externalrisk.domain.ExternalRiskProviderRequest;
import com.aifds.backend.externalrisk.domain.ExternalRiskProviderResponse;

public interface ExternalRiskLookupPort {

    ExternalRiskProviderResponse lookup(ExternalRiskProviderRequest request);
}
