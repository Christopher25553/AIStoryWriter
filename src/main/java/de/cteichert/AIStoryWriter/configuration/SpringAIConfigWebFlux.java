package de.cteichert.AIStoryWriter.configuration;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class SpringAIConfigWebFlux {

    @Bean
    public WebClient.Builder aiWebClientBuilder() {
        return WebClient.builder()
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create()
                                        .keepAlive(true)
                                        .responseTimeout(Duration.ofDays(2)) // hier dein langer Timeout
                        )
                );
    }

    @Bean
    public RestClient.Builder aiRestClientBuilder() {
        RequestConfig reqCfg = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofDays(2))
                .setConnectionRequestTimeout(Timeout.ofDays(2))
                .setResponseTimeout(Timeout.ofDays(2))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(reqCfg)
                .build();

        // RestClient unterstützt die Angabe einer RequestFactory — passe ggf. an deine Spring-Version an
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    @Bean
    public WebClient comfyUiWebClient() {
        return WebClient.builder()
                .baseUrl("http://localhost:8188")
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create()
                                        .keepAlive(true)
                                        .responseTimeout(Duration.ofDays(2)) // hier dein langer Timeout
                        )
                )
                .build();
    }
}
