package starbot;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.feed.AbstractWireFeedHttpMessageConverter;
import org.springframework.remoting.support.SimpleHttpServerFactoryBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.social.ApiException;
import org.springframework.social.twitter.api.Tweet;
import org.springframework.social.twitter.api.impl.TwitterTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.rometools.rome.feed.rss.Channel;

@SpringBootApplication
@Slf4j
@EnableScheduling
public class GithubStarbotApplication {

	@Bean
	Map<String, TwitterTemplate> twitterTemplates(TwitterApiConfig apiConfig) {
		return apiConfig.getApiInfo().entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, x -> {
					TwitterApiConfig.TwitterApiInfo appInfo = x.getValue();
					return new TwitterTemplate(appInfo.getAppId(), appInfo.getAppSecret(),
							appInfo.getAccessToken(), appInfo.getAccessTokenSecret());
				}));
	}

	@Bean
	ApplicationEventMulticaster applicationEventMulticaster(
			@Value("${event.consumer-threads:8}") int consumerThreads) {
		SimpleApplicationEventMulticaster eventMulticaster = new SimpleApplicationEventMulticaster();
		eventMulticaster.setTaskExecutor(Executors.newFixedThreadPool(consumerThreads));
		return eventMulticaster;
	}

	@Bean
	RestTemplate restTemplate() {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getMessageConverters()
				.add(new AbstractWireFeedHttpMessageConverter<Channel>(
						new MediaType("application", "xml", StandardCharsets.UTF_8)) {
					@Override
					protected boolean supports(Class<?> clazz) {
						return Channel.class.isAssignableFrom(clazz);
					}
				});
		return restTemplate;
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
	@Resource
	Map<String, TwitterTemplate> twitterTemplates;
	@Autowired
	RestTemplate restTemplate;
	@Autowired
	ApplicationEventPublisher publisher;
	@Autowired
	TwitterApiConfig apiConfig;

	@Scheduled(initialDelay = 0, fixedRate = 3600_000)
	public void check() {
		twitterTemplates.forEach((username, twitterTemplate) -> {
			TwitterApiConfig.Source source = apiConfig.getApiInfo().get(username)
					.getSource();
			publisher.publishEvent(source.createEvent.apply(username, twitterTemplate));
		});
	}

	@EventListener
	void handleGitHubEvent(GitHubEvent gitHubEvent) {
		String username = gitHubEvent.username;
		TwitterTemplate twitterTemplate = gitHubEvent.twitterTemplate;
		log.info("[{}] check github...", username);
		ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
				"https://api.github.com/users/{username}/starred", HttpMethod.GET, null,
				new ParameterizedTypeReference<List<Map<String, Object>>>() {
				}, Collections.singletonMap("username", username));
		List<Star> body = response.getBody().stream()
				.map(m -> new Star((String) m.get("full_name"),
						(String) m.get("html_url"), (String) m.get("description")))
				.collect(Collectors.toList());
		this.publishStars(username, twitterTemplate, body);
	}

	@EventListener
	void handleHatbuEvent(HatebuEvent hatebuEvent) {
		String username = hatebuEvent.username;
		TwitterTemplate twitterTemplate = hatebuEvent.twitterTemplate;
		log.info("[{}] check hatebu...", username);
		ResponseEntity<Channel> response = restTemplate.exchange(
				"http://b.hatena.ne.jp/{username}/rss?tag=java", HttpMethod.GET, null,
				Channel.class, Collections.singletonMap("username", username));
		List<Star> body = response.getBody()
				.getItems().stream().map(x -> new Star(x.getTitle().replace(" ", ""),
						x.getLink(), x.getDescription().getValue()))
				.collect(Collectors.toList());
		this.publishStars(username, twitterTemplate, body);
	}

	void publishStars(String username, TwitterTemplate twitterTemplate, List<Star> body) {
		try {
			List<Tweet> twitters = twitterTemplate.timelineOperations()
					.getUserTimeline(1);
			String lastName = twitters.size() > 0
					? twitters.get(0).getText().split("\\s")[0] : "";

			List<Star> stars = new ArrayList<>();
			for (Star star : body) {
				if (Objects.equals(lastName, star.name)) {
					break;
				}
				stars.add(star);
			}
			Collections.reverse(stars);
			log.info("[{}] found {}", username, stars);
			publisher.publishEvent(new StarEvent(username, stars, twitterTemplate));
		}
		catch (ApiException e) {
			log.warn("api error", e);
		}
	}
}

@Component
@Slf4j
class Twitterer {

	@EventListener
	void tweetStar(StarEvent starEvent) throws InterruptedException {
		for (Star star : starEvent.stars) {
			String text = star.name + " " + star.description;
			if (text.length() > 116) {
				text = text.substring(0, 113) + "...";
			}
			String status = text + " " + star.url;
			log.info("[{}] tweet {}", starEvent.username, status);
			starEvent.twitterTemplate.timelineOperations().updateStatus(status);
			// wait
			TimeUnit.SECONDS.sleep(2);
		}
	}
}

@AllArgsConstructor
class StarEvent {
	final String username;
	final List<Star> stars;
	final TwitterTemplate twitterTemplate;
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

@AllArgsConstructor
class GitHubEvent {
	final String username;
	final TwitterTemplate twitterTemplate;
}

@AllArgsConstructor
class HatebuEvent {
	final String username;
	final TwitterTemplate twitterTemplate;
}

@Component
@ConfigurationProperties("twitter")
@Data
class TwitterApiConfig {
	private Map<String, TwitterApiInfo> apiInfo = new LinkedHashMap<>();

	@AllArgsConstructor
	public enum Source {
		GITHUB(GitHubEvent::new), HATEBU(HatebuEvent::new);

		final BiFunction<String, TwitterTemplate, ?> createEvent;
	}

	@Data
	public static class TwitterApiInfo {
		private Source source = Source.GITHUB;
		private String appId;
		private String appSecret;
		private String accessToken;
		private String accessTokenSecret;
	}
}
