package dev.docassist.common.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Voyage AI embeddings (Anthropic's recommended embedding provider), requested at 512 dimensions. */
public class VoyageEmbedder implements Embedder {

    private static final int BATCH = 64;

    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public VoyageEmbedder(String apiKey, String model, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public String modelId() {
        return model + "-" + DIMENSIONS;
    }

    @Override
    public float[] embedQuery(String text) {
        return call(List.of(text), "query").get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            out.addAll(call(texts.subList(i, Math.min(texts.size(), i + BATCH)), "document"));
        }
        return out;
    }

    private List<float[]> call(List<String> texts, String inputType) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("input_type", inputType);
            body.put("output_dimension", DIMENSIONS);
            body.set("input", mapper.valueToTree(texts));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.voyageai.com/v1/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("authorization", "Bearer " + apiKey)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Voyage returned " + resp.statusCode());
            }
            List<float[]> out = new ArrayList<>();
            for (JsonNode item : mapper.readTree(resp.body()).path("data")) {
                JsonNode emb = item.path("embedding");
                float[] v = new float[emb.size()];
                for (int i = 0; i < v.length; i++) v[i] = (float) emb.get(i).asDouble();
                out.add(v);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Voyage embeddings failed: " + e.getMessage(), e);
        }
    }
}
