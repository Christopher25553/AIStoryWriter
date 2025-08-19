package de.cteichert.AIStoryWriter.model;

import java.util.List;

public record StoryResult(String storyTitle, List<SceneDto> scenes) {
}