package dev.docassist.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.docassist.common.embedding.CachingEmbedder;
import dev.docassist.common.embedding.Embedder;
import dev.docassist.common.embedding.HashingEmbedder;
import dev.docassist.common.embedding.VoyageEmbedder;
import dev.docassist.common.guardrails.GroundingChecker;
import dev.docassist.common.guardrails.InjectionScanner;
import dev.docassist.common.guardrails.PiiRedactor;
import dev.docassist.common.llm.AnthropicClient;
import dev.docassist.common.llm.CostCalculator;
import dev.docassist.common.llm.ExtractiveAnswerer;
import dev.docassist.common.llm.LlmClient;
import dev.docassist.common.llm.MockLlmClient;
import dev.docassist.common.retrieval.HeuristicReranker;
import dev.docassist.common.retrieval.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

@Configuration
@EnableConfigurationProperties(DocassistProperties.class)
public class CommonConfig {

    private static final Logger log = LoggerFactory.getLogger(CommonConfig.class);

    @Bean
    public Embedder embedder(DocassistProperties props, StringRedisTemplate redis, ObjectMapper mapper) {
        DocassistProperties.Embeddings e = props.getEmbeddings();
        Embedder base;
        if ("voyage".equalsIgnoreCase(e.getProvider())) {
            if (e.getVoyageApiKey() == null || e.getVoyageApiKey().isBlank()) {
                throw new IllegalStateException("DOCASSIST_EMBEDDINGS_PROVIDER=voyage but VOYAGE_API_KEY is empty");
            }
            base = new VoyageEmbedder(e.getVoyageApiKey(), e.getVoyageModel(), mapper);
        } else {
            base = new HashingEmbedder();
        }
        log.info("embeddings: {}", base.modelId());
        return new CachingEmbedder(base, redis);
    }

    @Bean
    public ExtractiveAnswerer extractiveAnswerer(DocassistProperties props) {
        return new ExtractiveAnswerer(Set.copyOf(props.getLowInformationTerms()));
    }

    @Bean
    public LlmClient llmClient(DocassistProperties props, ObjectMapper mapper, ExtractiveAnswerer extractive) {
        DocassistProperties.Llm llm = props.getLlm();
        boolean hasKey = llm.getApiKey() != null && !llm.getApiKey().isBlank();
        String provider = llm.getProvider() == null ? "auto" : llm.getProvider().toLowerCase();
        boolean useAnthropic = provider.equals("anthropic") || (provider.equals("auto") && hasKey);
        if (useAnthropic && !hasKey) {
            throw new IllegalStateException("DOCASSIST_LLM_PROVIDER=anthropic but ANTHROPIC_API_KEY is empty");
        }
        LlmClient client = useAnthropic ? new AnthropicClient(llm, mapper) : new MockLlmClient(mapper, extractive);
        log.info("llm: {} (model={}, fast={})", client.name(), client.model(), client.fastModel());
        return client;
    }

    @Bean
    public CostCalculator costCalculator(DocassistProperties props) {
        CostCalculator calc = new CostCalculator();
        props.getPrices().forEach((model, p) -> calc.setPrice(model,
                new CostCalculator.Price(p.getInput(), p.getOutput(), p.getCacheReadMultiplier(), p.getCacheWriteMultiplier())));
        return calc;
    }

    @Bean
    public Reranker reranker() {
        return new HeuristicReranker();
    }

    @Bean
    public InjectionScanner injectionScanner() {
        return new InjectionScanner();
    }

    @Bean
    public PiiRedactor piiRedactor() {
        return new PiiRedactor();
    }

    @Bean
    public GroundingChecker groundingChecker() {
        return new GroundingChecker();
    }
}
