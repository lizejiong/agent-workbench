package com.lzj.rag.backend.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.knowledgebase.KnowledgeBaseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
@EnableConfigurationProperties(EmbeddingCatalog.Properties.class)
public class EmbeddingCatalog {
    @Validated
    @ConfigurationProperties("kb.models")
    public record Properties(@NotBlank String defaultProfile, @NotEmpty Map<@NotBlank String, @Valid Model> profiles) { }
    public record Model(boolean enabled, @NotBlank String modelId, @NotBlank String provider,
                        @NotBlank String modelName, @Min(1024) @Max(1024) int dimension,
                        @NotBlank String embeddingSpace, @NotBlank String configVersion) { }
    public record Profile(String id, String modelId, String provider, String modelName, int dimension,
                          String embeddingSpace, String configVersion, String configRevision) { }
    public record Binding(Profile profile, String snapshot) { }

    private final Properties properties;
    private final ObjectMapper json;
    public EmbeddingCatalog(Properties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        if (!properties.profiles().containsKey(properties.defaultProfile())) {
            throw new IllegalStateException("Default embedding profile is not configured");
        }
    }
    public Binding resolve(String requested) {
        String id = requested == null ? properties.defaultProfile() : requested;
        Model model = properties.profiles().get(id);
        if (model == null || !model.enabled()) {
            throw new KnowledgeBaseException(400, "KB_PROFILE_NOT_ALLOWED", "Select an enabled server embedding profile");
        }
        try {
            String snapshot = json.writeValueAsString(model);
            String revision = sha256(snapshot);
            return new Binding(new Profile(id, model.modelId(), model.provider(), model.modelName(), model.dimension(),
                    model.embeddingSpace(), model.configVersion(), revision), snapshot);
        } catch (JsonProcessingException error) { throw new IllegalStateException("Invalid embedding configuration", error); }
    }
    public List<Profile> available() {
        return properties.profiles().entrySet().stream().filter(e -> e.getValue().enabled())
                .map(e -> resolve(e.getKey()).profile()).sorted(Comparator.comparing(Profile::id)).toList();
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
