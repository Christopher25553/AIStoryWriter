package de.cteichert.AIStoryWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cteichert.AIStoryWriter.model.StoryRequest;
import de.cteichert.AIStoryWriter.model.StoryResult;
import de.cteichert.AIStoryWriter.service.StoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StoryServiceIntegrationTest {
    @Autowired
    StoryService storyService;

    @Autowired
    ObjectMapper objectMapper;

    @Value("${localModel}")
    String model;

    @Test
    void generateStory_optionalLocalLMStudioAndComfyUI() throws Exception {
        int scenes = 8;

        StoryRequest req = new StoryRequest(
                "Epic Adventure",
                """
                        fantasy, medieval, magic, demons, demon king, magical enchanted sword, hero
                        """,
                """
                        Schreibe den Text ausschließlich auf Deutsch
                        ***Jede Szene muss MINDESTENS 500 Wörter beinhalten***
                        """,
                """
                        Cinematic, highly detailed
                        """,
                scenes,
                """
                        mystic, grim
                        """,
                model
        );

        // StoryService liefert jetzt Mono<StoryResult>
        Mono<StoryResult> resMono = storyService.generateStory(req);

        // Für Tests kann man block() nutzen, oder StepVerifier für reaktives Testen
        StoryResult res = resMono.block(); // Timeout optional
        assertThat(res).isNotNull();

        System.out.println(objectMapper.writeValueAsString(res));

        File resultFile = new File("./Story/story.json");
        resultFile.getParentFile().mkdirs();
        objectMapper.writeValue(resultFile, res);

        assertThat(res).isNotNull();
        assertThat(res.storyTitle()).isEqualTo("Epic Adventure");
        assertThat(res.scenes()).hasSize(scenes);
        res.scenes().forEach(scene -> {
            assertThat(scene.text()).isNotEmpty();
            assertThat(scene.imagePath()).isNotEmpty();
        });
    }
}
