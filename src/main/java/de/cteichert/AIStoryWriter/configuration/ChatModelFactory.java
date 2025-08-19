package de.cteichert.AIStoryWriter.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class ChatModelFactory {
    private final WebClient.Builder aiWebClientBuilder;
    private final RestClient.Builder aiRestClientBuilder;

    @Value("${app.llm.base-url:http://localhost:1234}")
    private String baseUrl;

    @Value("${app.llm.api-key:}")
    private String apiKey;

    /**
     * Erzeugt ein OpenAiChatModel. Du kannst hier LM Studio über baseUrl angeben.
     */
    public OpenAiChatModel create(String modelName) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName != null && !modelName.isBlank() ? modelName : "gpt-oss-20B")
                .temperature(0.6)
                .build();

        return OpenAiChatModel.builder()
                .defaultOptions(options)
                .openAiApi(OpenAiApi.builder()
                        .restClientBuilder(aiRestClientBuilder)
                        .webClientBuilder(aiWebClientBuilder)
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build())
                .build();
    }
}