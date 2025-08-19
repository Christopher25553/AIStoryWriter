package de.cteichert.AIStoryWriter.controller;

import de.cteichert.AIStoryWriter.model.StoryRequest;
import de.cteichert.AIStoryWriter.model.StoryResult;
import de.cteichert.AIStoryWriter.service.StoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/story")
public class StoryController {
    private final StoryService storyService;

    public StoryController(StoryService storyService) {
        this.storyService = storyService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Mono<StoryResult>> generate(@RequestBody StoryRequest req) {
        Mono<StoryResult> res = storyService.generateStory(req);
        return ResponseEntity.ok(res);
    }
}