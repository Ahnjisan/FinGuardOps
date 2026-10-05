package com.aifds.backend.aireport.client;

import com.aifds.backend.aireport.config.AiReportProperties;
import com.aifds.backend.aireport.dto.AiReportDtos;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiReportHttpClient {
    private final RestClient identityClient;
    private final RestClient generationClient;

    public AiReportHttpClient(AiReportProperties.Values properties) {
        SimpleClientHttpRequestFactory identityFactory = new SimpleClientHttpRequestFactory();
        identityFactory.setConnectTimeout(1000);
        identityFactory.setReadTimeout(2000);
        this.identityClient = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(identityFactory).build();
        SimpleClientHttpRequestFactory generationFactory = new SimpleClientHttpRequestFactory();
        generationFactory.setConnectTimeout(2000);
        generationFactory.setReadTimeout(240000);
        this.generationClient = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(generationFactory).build();
    }

    public AiReportDtos.ModelIdentity identity() {
        return identityClient.get().uri("/api/v1/ai-reports/model")
                .retrieve().body(AiReportDtos.ModelIdentity.class);
    }

    public AiReportDtos.GenerationResult generate(AiReportDtos.GenerationRequest request) {
        return generationClient.post().uri("/api/v1/ai-reports")
                .body(request).retrieve().body(AiReportDtos.GenerationResult.class);
    }
}
