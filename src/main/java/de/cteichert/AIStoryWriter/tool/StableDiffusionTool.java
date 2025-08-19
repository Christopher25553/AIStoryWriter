package de.cteichert.AIStoryWriter.tool;


import de.cteichert.AIStoryWriter.service.StableDiffusionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Data
@RequiredArgsConstructor
public class StableDiffusionTool {
    private final StableDiffusionService stableDiffusionService;

    private String modelName = "Juggernaut-XI-byRunDiffusion.safetensors";

    /**
     * Blocking wrapper: ruft den Service auf und blockiert bis Pfad zurückkommt oder Timeout.
     */
    public String generateImageBlocking(String prompt, String negPrompt, int width, int height, Duration timeout) {
        // block(timeout) wirft ein Exception bei Timeout
        String path = stableDiffusionService.generateImageWithComfyUI(modelName, prompt, negPrompt, width, height)
                .block(timeout);
        if (path == null) {
            throw new RuntimeException("ComfyUI returned null path");
        }
        return path;
    }
}