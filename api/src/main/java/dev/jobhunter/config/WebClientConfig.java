package dev.jobhunter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Value("${crawl.timeout-seconds:30}")
    private int timeoutSeconds;

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB - Greenhouse boards with content can exceed 30MB
                .build();

        return builder
                .clientConnector(new JdkClientHttpConnector(jdkClient))
                .exchangeStrategies(strategies)
                .filter(new RetryableWebClientFilter())
                .build();
    }
}
