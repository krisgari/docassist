package dev.docassist.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "docassist")
public class DocassistProperties {

    private String tenant = "acme";
    private final Llm llm = new Llm();
    private final Embeddings embeddings = new Embeddings();
    private final Retrieval retrieval = new Retrieval();
    private Map<String, PriceProps> prices = new HashMap<>();
    private java.util.List<String> lowInformationTerms = new java.util.ArrayList<>();

    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public Llm getLlm() { return llm; }
    public Embeddings getEmbeddings() { return embeddings; }
    public Retrieval getRetrieval() { return retrieval; }
    public Map<String, PriceProps> getPrices() { return prices; }
    public void setPrices(Map<String, PriceProps> prices) { this.prices = prices; }
    public java.util.List<String> getLowInformationTerms() { return lowInformationTerms; }
    public void setLowInformationTerms(java.util.List<String> v) { this.lowInformationTerms = v; }

    public static class Llm {
        private String provider = "auto";
        private String apiKey = "";
        private String baseUrl = "https://api.anthropic.com";
        private String model = "claude-sonnet-5-5";
        private String fastModel = "claude-haiku-5-5";
        private int maxTokens = 8192;
        private int timeoutSeconds = 60;
        private int maxRetries = 2;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFastModel() { return fastModel; }
        public void setFastModel(String fastModel) { this.fastModel = fastModel; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Embeddings {
        private String provider = "local";
        private String voyageApiKey = "";
        private String voyageModel = "voyage-3.5-lite";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getVoyageApiKey() { return voyageApiKey; }
        public void setVoyageApiKey(String voyageApiKey) { this.voyageApiKey = voyageApiKey; }
        public String getVoyageModel() { return voyageModel; }
        public void setVoyageModel(String voyageModel) { this.voyageModel = voyageModel; }
    }

    public static class Retrieval {
        private int denseK = 50;
        private int lexicalK = 50;
        private int topK = 8;
        private int maxContextTokens = 6000;
        private int retrievalCacheTtlSeconds = 600;
        private int groupCacheTtlSeconds = 60;

        public int getDenseK() { return denseK; }
        public void setDenseK(int denseK) { this.denseK = denseK; }
        public int getLexicalK() { return lexicalK; }
        public void setLexicalK(int lexicalK) { this.lexicalK = lexicalK; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
        public int getRetrievalCacheTtlSeconds() { return retrievalCacheTtlSeconds; }
        public void setRetrievalCacheTtlSeconds(int v) { this.retrievalCacheTtlSeconds = v; }
        public int getGroupCacheTtlSeconds() { return groupCacheTtlSeconds; }
        public void setGroupCacheTtlSeconds(int v) { this.groupCacheTtlSeconds = v; }
    }

    public static class PriceProps {
        private double input;
        private double output;
        private double cacheReadMultiplier = 0.1;
        private double cacheWriteMultiplier = 1.25;

        public double getInput() { return input; }
        public void setInput(double input) { this.input = input; }
        public double getOutput() { return output; }
        public void setOutput(double output) { this.output = output; }
        public double getCacheReadMultiplier() { return cacheReadMultiplier; }
        public void setCacheReadMultiplier(double v) { this.cacheReadMultiplier = v; }
        public double getCacheWriteMultiplier() { return cacheWriteMultiplier; }
        public void setCacheWriteMultiplier(double v) { this.cacheWriteMultiplier = v; }
    }
}
