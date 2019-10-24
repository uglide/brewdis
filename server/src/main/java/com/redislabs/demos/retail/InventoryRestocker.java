package com.redislabs.demos.retail;

import static com.redislabs.demos.retail.Field.ON_HAND;
import static com.redislabs.demos.retail.Field.SKU;
import static com.redislabs.demos.retail.Field.STORE;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class InventoryRestocker
		implements InitializingBean, DisposableBean, StreamListener<String, MapRecord<String, String, String>> {

	@Autowired
	private StringRedisTemplate template;
	@Autowired
	private BrewdisConfig config;
	private ScheduledExecutorService executor;

	private OfInt delays;
	private Subscription subscription;
	private OfInt onHands;
	private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
	private Map<String, ZonedDateTime> scheduledRestocks = new HashMap<>();

	@Override
	public void afterPropertiesSet() throws Exception {
		Random random = new Random();
		this.delays = random.ints(config.getInventory().getRestock().getDelayMin(),
				config.getInventory().getRestock().getDelayMax()).iterator();
		this.onHands = random.ints(config.getInventory().getRestock().getDeltaMin(),
				config.getInventory().getRestock().getDeltaMax()).iterator();
		this.container = StreamMessageListenerContainer.create(template.getConnectionFactory(),
				StreamMessageListenerContainerOptions.builder().pollTimeout(Duration.ofMillis(10000)).build());
		container.start();
		this.subscription = container.receive(StreamOffset.latest(config.getInventory().getOutputStream()), this);
		subscription.await(Duration.ofSeconds(2));
		executor = Executors.newSingleThreadScheduledExecutor();
	}

	@Override
	public void destroy() throws Exception {
		if (executor != null) {
			executor.shutdownNow();
		}
		if (subscription != null) {
			subscription.cancel();
		}
		if (container != null) {
			container.stop();
		}
	}

	@Override
	public void onMessage(MapRecord<String, String, String> message) {
		String store = message.getValue().get(STORE);
		String sku = message.getValue().get(SKU);
		int available = Integer.parseInt(message.getValue().get(Field.AVAILABLE_TO_PROMISE));
		if (available < config.getInventory().getRestock().getThreshold()) {
			String id = store + ":" + sku;
			if (scheduledRestocks.containsKey(id)) {
				return;
			}
			scheduledRestocks.put(id, ZonedDateTime.now());
			int delay = delays.nextInt();
			log.info("Scheduling restocking for {}:{} in {} seconds", store, sku, delay);
			executor.schedule(new Runnable() {

				@Override
				public void run() {
					int delta = onHands.nextInt();
					template.opsForStream().add(config.getInventory().getInputStream(),
							Map.of(STORE, store, SKU, sku, ON_HAND, String.valueOf(delta)));
					scheduledRestocks.remove(id);
				}
			}, delay, TimeUnit.SECONDS);
		}
	}

}
