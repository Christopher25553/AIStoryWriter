package de.cteichert.AIStoryWriter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class StableDiffusionService {
    private final WebClient comfyUiWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String WORKFLOW_PATH = "stable-diffusion-workflows/default.json";
    private static final String IMAGE_OUTPUT_DIR = "D:/ComfyUI/output"; // anpassen falls nötig

    public StableDiffusionService(WebClient comfyUiWebClient) {
        this.comfyUiWebClient = comfyUiWebClient;
    }

    /**
     * Verbessertes, instrumentiertes ComfyUI-Polling:
     * - loggt POST-Response / promptId
     * - pollt /history/{id} (falls vorhanden) und fallback: sucht Dateien im IMAGE_OUTPUT_DIR mit prefix imageId
     * - viel mehr debug-Logs für die Fehlersuche
     */
    public Mono<String> generateImageWithComfyUI(String modelName, String prompt, String negativePrompt, int width, int height) {
        log.info("Generating image - width: {}, height: {}, prompt: '{}'", width, height, prompt);

        final String imageId = UUID.randomUUID().toString();
        final Duration submitTimeout = Duration.ofSeconds(20);
        final Duration pollTimeout = Duration.ofMinutes(10);
        final long pollIntervalMillis = 800L;

        return Mono.fromCallable(() -> {
            // --- Build workflow JSON (wie vorher) ---
            File jsonFile = new ClassPathResource(WORKFLOW_PATH).getFile();
            JsonNode workflow = objectMapper.readTree(jsonFile);

            int seed = new Random().nextInt(Integer.MAX_VALUE);
            ((ObjectNode) workflow.get("3").get("inputs")).put("seed", seed);
            ((ObjectNode) workflow.get("4").get("inputs")).put("ckpt_name", modelName);
            ((ObjectNode) workflow.get("6").get("inputs")).put("text", prompt.replace("\"", "'").replace("\\", ""));
            if (StringUtils.isNotBlank(negativePrompt)) {
                ((ObjectNode) workflow.get("7").get("inputs")).put("text", negativePrompt.replace("\"", "'").replace("\\", ""));
            }
            ObjectNode latentImageInputs = (ObjectNode) workflow.get("5").get("inputs");
            latentImageInputs.put("width", width);
            latentImageInputs.put("height", height);

            ((ObjectNode) workflow.get("9").get("inputs")).put("filename_prefix", imageId);

            ObjectNode request = objectMapper.createObjectNode();
            request.set("prompt", workflow);

            // --- Ensure output dir exists / is readable ---
            File folder = new File(IMAGE_OUTPUT_DIR);
            if (!folder.exists()) {
                log.warn("IMAGE_OUTPUT_DIR does not exist: {} (will attempt to create)", IMAGE_OUTPUT_DIR);
                boolean created = folder.mkdirs();
                log.info("IMAGE_OUTPUT_DIR created? {}", created);
            }
            if (!folder.canRead()) {
                log.warn("IMAGE_OUTPUT_DIR is not readable by process: {}", IMAGE_OUTPUT_DIR);
            }

            // --- POST to ComfyUI and capture response (as JSON if possible) ---
            JsonNode submitResp = null;
            try {
                log.info("Posting prompt to ComfyUI (/prompt). imageId={}", imageId);
                submitResp = comfyUiWebClient.post()
                        .uri("/prompt")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(submitTimeout);
            } catch (Exception ex) {
                log.error("Error while POST /prompt to ComfyUI: {}", ex, ex);
            }

            // Try to extract possible direct outputs from submit response
            if (submitResp != null) {
                if (submitResp.has("outputs")) {
                    String path = tryExtractPathFromOutputs(submitResp.get("outputs"));
                    if (path != null) {
                        log.info("ComfyUI returned outputs immediately for imageId {} -> {}", imageId, path);
                        return path;
                    }
                }
            }

            // Extract possible prompt/job id
            String promptId = null;
            if (submitResp != null) {
                if (submitResp.has("id")) {
                    promptId = submitResp.get("id").asText(null);
                } else if (submitResp.has("job_id")) {
                    promptId = submitResp.get("job_id").asText(null);
                } else if (submitResp.has("uuid")) {
                    promptId = submitResp.get("uuid").asText(null);
                } else if (submitResp.has("prompt_id")) {
                    promptId = submitResp.get("prompt_id").asText(null);
                }
            }
            log.info("Using imageId={}, promptId={}", imageId, promptId);

            // --- Poll loop: prefer history endpoint if we have promptId ---
            long start = System.currentTimeMillis();
            int attempt = 0;
            while (System.currentTimeMillis() - start < pollTimeout.toMillis()) {
                attempt++;
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Interrupted while waiting for ComfyUI output");
                }

                // 1) If promptId available -> try /history/{id}
                if (promptId != null) {
                    try {
                        JsonNode hist = comfyUiWebClient.get()
                                .uri("/history/{id}", promptId)
                                .retrieve()
                                .bodyToMono(JsonNode.class)
                                .block(Duration.ofSeconds(15));

                        log.debug("ComfyUI /history attempt #{} for promptId {} -> short: {}", attempt, promptId,
                                hist == null ? "null" : hist.toString().substring(0, Math.min(800, hist.toString().length())));

                        if (hist != null) {
                            // check common places for outputs
                            if (hist.has("outputs")) {
                                String path = tryExtractPathFromOutputs(hist.get("outputs"));
                                if (path != null) {
                                    log.info("Found image via /history.outputs for promptId {} -> {}", promptId, path);
                                    return path;
                                }
                            }
                            if (hist.has("executions") && hist.get("executions").isArray()) {
                                for (JsonNode exec : hist.get("executions")) {
                                    if (exec.has("outputs")) {
                                        String path = tryExtractPathFromOutputs(exec.get("outputs"));
                                        if (path != null) {
                                            log.info("Found image via /history.executions for promptId {} -> {}", promptId, path);
                                            return path;
                                        }
                                    }
                                }
                            }
                            // some installations return { promptId: { outputs: [...] } }
                            if (hist.has(promptId)) {
                                JsonNode node = hist.get(promptId);
                                if (node != null && node.has("outputs")) {
                                    String path = tryExtractPathFromOutputs(node.get("outputs"));
                                    if (path != null) {
                                        log.info("Found image via history[promptId].outputs for promptId {} -> {}", promptId, path);
                                        return path;
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Error while querying /history/{} (attempt {}): {}", promptId, attempt, ex.toString());
                        // continue to filesystem polling as fallback
                    }
                }

                // 2) Filesystem polling: list files that match imageId prefix
                File[] matchingFiles = new File(IMAGE_OUTPUT_DIR).listFiles((dir, name) ->
                        name.toLowerCase().endsWith(".png") && name.startsWith(imageId));
                int found = matchingFiles == null ? 0 : matchingFiles.length;
                log.debug("Poll attempt #{}: found {} matching files for imageId {} in {}", attempt, found, imageId, IMAGE_OUTPUT_DIR);
                if (found > 0) {
                    // choose first file
                    File imageFile = matchingFiles[0];
                    // quick stability check
                    long prev = -1;
                    for (int i = 0; i < 6; i++) {
                        long s = imageFile.length();
                        if (s > 0 && s == prev) {
                            break;
                        }
                        prev = s;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ie;
                        }
                    }
                    log.info("Found image file for imageId {} -> {}", imageId, imageFile.getAbsolutePath());
                    return imageFile.getAbsolutePath();
                }

                // nothing yet -> sleep and loop
                try {
                    Thread.sleep(pollIntervalMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }

            throw new TimeoutException("Timeout waiting for ComfyUI result (imageId=" + imageId + ", promptId=" + promptId + ")");
        }).subscribeOn(Schedulers.boundedElastic()); // run blocking work on boundedElastic
    }

    private String tryExtractPathFromOutputs(JsonNode outputs) {
        // einfache Heuristik: outputs kann eine Liste mit "path" oder "file" Feldern sein
        if (outputs == null) {
            return null;
        }
        if (outputs.isArray()) {
            for (JsonNode out : outputs) {
                if (out.has("path")) {
                    return out.get("path").asText();
                }
                if (out.has("file")) {
                    return out.get("file").asText();
                }
                if (out.has("image") && out.get("image").has("path")) {
                    return out.get("image").get("path").asText();
                }
            }
        } else if (outputs.isObject()) {
            if (outputs.has("path")) {
                return outputs.get("path").asText();
            }
        }
        return null;
    }
}