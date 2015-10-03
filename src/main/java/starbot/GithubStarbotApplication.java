package starbot;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.remoting.support.SimpleHttpServerFactoryBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.social.twitter.api.Tweet;
import org.springframework.social.twitter.api.impl.TwitterTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@Slf4j
@EnableScheduling
public class GithubStarbotApplication {

	@Bean
	TwitterTemplate twitterTemplate(@Value("${twitter.app-id}") String appId,
			@Value("${twitter.app-secret}") String appSecret,
			@Value("${twitter.access-token}") String accessToken,
			@Value("${twitter.access-token-secret}") String accessTokenSecret) {
		TwitterTemplate template = new TwitterTemplate(appId, appSecret, accessToken,
				accessTokenSecret);
		template.getRestTemplate().setErrorHandler(new DefaultResponseErrorHandler() {
			@Override
			public void handleError(ClientHttpResponse response) throws IOException {
				log.warn("CODE={},TEXT={}", response.getStatusCode(),
						response.getStatusText());
			}
		});
		return template;
	}

	@Bean
	ApplicationEventMulticaster applicationEventMulticaster() {
		SimpleApplicationEventMulticaster eventMulticaster = new SimpleApplicationEventMulticaster();
		eventMulticaster.setTaskExecutor(Executors.newSingleThreadExecutor());
		return eventMulticaster;
	}

	@Bean
	RestTemplate restTemplate() {
		return new RestTemplate();
	}

	@Bean
	SimpleHttpServerFactoryBean httpServer(
			@Value("${PORT:${server.port:8080}}") int port) {
		SimpleHttpServerFactoryBean factoryBean = new SimpleHttpServerFactoryBean();
		factoryBean.setPort(port);
		factoryBean.setContexts(Collections.singletonMap("/", (exec) -> {
			String response = "Hello GitHub Starbot";
			exec.sendResponseHeaders(200, response.length());
			try (OutputStream stream = exec.getResponseBody()) {
				stream.write(response.getBytes());
			}
		}));
		return factoryBean;
	}

	public static void main(String[] args) {
		SpringApplication.run(GithubStarbotApplication.class, args);
	}
}

@Component
@Slf4j
class StarChecker {
	@Autowired
	TwitterTemplate twitterTemplate;
	@Autowired
	RestTemplate restTemplate;
	@Autowired
	ApplicationEventPublisher publisher;
	@Value("${github.username}")
	String username;

	@Scheduled(initialDelay = 0, fixedRate = 3600_000)
	public void check() {
		ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
				"https://api.github.com/users/{username}/starred", HttpMethod.GET, null,
				new ParameterizedTypeReference<List<Map<String, Object>>>() {
				}, Collections.singletonMap("username", username));
		log.info("check...");
		List<Star> body = response.getBody().stream()
				.map(m -> new Star((String) m.get("full_name"),
						(String) m.get("html_url"), (String) m.get("description")))
				.collect(Collectors.toList());

		List<Tweet> twitters = twitterTemplate.timelineOperations().getUserTimeline(1);
		String lastName = twitters.size() > 0 ? twitters.get(0).getText().split("\\s")[0]
				: "";

		List<Star> stars = new ArrayList<>();
		for (Star star : body) {
			if (Objects.equals(lastName, star.name)) {
				break;
			}
			stars.add(star);
		}
		Collections.reverse(stars);
		log.info("found {}", stars);

		stars.forEach(publisher::publishEvent);
	}

}

@Component
@Slf4j
class StarTweet {
	@Autowired
	TwitterTemplate twitterTemplate;

	@EventListener
	void tweetStar(Star star) throws InterruptedException {
		String text = star.name + " " + star.description;
		if (text.length() > 116) {
			text = text.substring(0, 113) + "...";
		}
		String status = text + " " + star.url;
		log.info("tweet {}", status);
		twitterTemplate.timelineOperations().updateStatus(status);
		// wait
		TimeUnit.SECONDS.sleep(2);
	}
}

@AllArgsConstructor
class Star {
	final String name;
	final String url;
	final String description;

	@Override
	public String toString() {
		return name;
	}
}
