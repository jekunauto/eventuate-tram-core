package io.eventuate.tram.rabbitmq.integrationtests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.eventuate.javaclient.commonimpl.JSonMapper;
import io.eventuate.tram.consumer.common.DuplicateMessageDetector;
import io.eventuate.tram.consumer.rabbitmq.MessageConsumerRabbitMQImpl;
import io.eventuate.tram.data.producer.rabbitmq.EventuateRabbitMQProducer;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.util.test.async.Eventually;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = MessagingTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class MessagingTest {

  @Configuration
  @EnableAutoConfiguration
  public static class Config {
    @Bean
    public EventuateRabbitMQProducer rabbitMQMessageProducer(@Value("${rabbitmq.url}") String rabbitMQURL) {
      return new EventuateRabbitMQProducer(rabbitMQURL);
    }

    @Bean
    public DuplicateMessageDetector duplicateMessageDetector() {
      return (consumerId, messageId) -> false;
    }
  }

  private static class EventuallyConfig {
    public final int iterations;
    public final int timeout;
    public final TimeUnit timeUnit;

    public EventuallyConfig(int iterations, int timeout, TimeUnit timeUnit) {
      this.iterations = iterations;
      this.timeout = timeout;
      this.timeUnit = timeUnit;
    }
  }

  private static class TestSubscription {
    private MessageConsumerRabbitMQImpl consumer;
    private ConcurrentLinkedQueue<Integer> messageQueue;

    public TestSubscription(MessageConsumerRabbitMQImpl consumer, ConcurrentLinkedQueue<Integer> messageQueue) {
      this.consumer = consumer;
      this.messageQueue = messageQueue;
    }

    public MessageConsumerRabbitMQImpl getConsumer() {
      return consumer;
    }

    public ConcurrentLinkedQueue<Integer> getMessageQueue() {
      return messageQueue;
    }

    public void clearMessages() {
      messageQueue.clear();
    }

    public void close() {
      consumer.close();
      messageQueue.clear();
    }
  }

  private Logger logger = LoggerFactory.getLogger(getClass());

  @Value("${rabbitmq.url}")
  private String rabbitMQURL;

  @Value("${eventuatelocal.zookeeper.connection.string}")
  private String zkUrl;

  @Autowired
  private EventuateRabbitMQProducer eventuateRabbitMQProducer;

  @Autowired
  private ApplicationContext applicationContext;

  private static final int MESSAGE_COUNT = 100;
  private static final int REBALANCE_TIMEOUT_IN_MILLIS = 10000;
  private static final EventuallyConfig EVENTUALLY_CONFIG = new EventuallyConfig(100, 1, TimeUnit.SECONDS);


  private String destination;
  private String subscriberId;

  @Before
  public void init() {
    destination = "destination" + UUID.randomUUID();
    subscriberId = "subscriber" + UUID.randomUUID();
  }

  @Test
  public void test1Consumer2Partitions() throws Exception {
    TestSubscription subscription = subscribe(2);

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(subscription);
  }

  @Test
  public void test2Consumers2Partitions() throws Exception {
    TestSubscription subscription1 = subscribe(2);
    TestSubscription subscription2 = subscribe(2);

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(ImmutableList.of(subscription1, subscription2));
  }

  @Test
  public void test1Consumer2PartitionsThenAddedConsumer() throws Exception {
    TestSubscription testSubscription1 = subscribe(2);

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(testSubscription1);

    testSubscription1.clearMessages();
    TestSubscription testSubscription2 = subscribe(2);

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(ImmutableList.of(testSubscription1, testSubscription2));
  }

  @Test
  public void test2Consumers2PartitionsThenRemovedConsumer() throws Exception {

    TestSubscription testSubscription1 = subscribe(2);
    TestSubscription testSubscription2 = subscribe(2);

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(ImmutableList.of(testSubscription1, testSubscription2));

    testSubscription1.clearMessages();
    testSubscription2.close();

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(testSubscription1);
  }

  @Test
  public void test5Consumers9PartitionsThenRemoved2ConsumersAndAdded3Consumers() throws Exception {

    LinkedList<TestSubscription> testSubscriptions = createConsumersAndSubscribe(5, 9);

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(testSubscriptions);

    for (int i = 0; i < 2; i++) {
      testSubscriptions.poll().close();
    }

    testSubscriptions.forEach(TestSubscription::clearMessages);

    testSubscriptions.addAll(createConsumersAndSubscribe(3, 9));

    waitForRebalance();

    sendMessages();

    assertMessagesConsumed(testSubscriptions);
  }

  private void assertMessagesConsumed(TestSubscription testSubscription) {
    Eventually.eventually(EVENTUALLY_CONFIG.iterations,
            EVENTUALLY_CONFIG.timeout,
            EVENTUALLY_CONFIG.timeUnit,
            () -> Assert.assertEquals(String.format("consumer %s did not receive expected messages", testSubscription.getConsumer().id),
                    MESSAGE_COUNT,
                    testSubscription.messageQueue.size()));
  }

  private void assertMessagesConsumed(List<TestSubscription> testSubscriptions) {
    Eventually.eventually(EVENTUALLY_CONFIG.iterations,
            EVENTUALLY_CONFIG.timeout,
            EVENTUALLY_CONFIG.timeUnit,
            () -> {

      List<TestSubscription> emptySubscriptions = testSubscriptions
              .stream()
              .filter(testSubscription -> testSubscription.messageQueue.isEmpty())
              .collect(Collectors.toList());

      emptySubscriptions.forEach(testSubscription -> logger.info("[{}] consumer is empty", testSubscription.getConsumer().id));

      Assert.assertTrue(emptySubscriptions.isEmpty());

      Assert.assertEquals((long) MESSAGE_COUNT,
              (long) testSubscriptions
                      .stream()
                      .map(testSubscription -> testSubscription.getMessageQueue().size())
                      .reduce((a, b) -> a + b)
                      .orElse(0));
    });
  }

  private void waitForRebalance() throws Exception {
    Thread.sleep(REBALANCE_TIMEOUT_IN_MILLIS);
  }

  private LinkedList<TestSubscription> createConsumersAndSubscribe(int consumerCount, int partitionCount) {

    LinkedList<TestSubscription> subscriptions = new LinkedList<>();

    for (int i = 0; i < consumerCount; i++) {
      subscriptions.add(subscribe(partitionCount));
    }

    return subscriptions;
  }

  private TestSubscription subscribe(int partitionCount) {
    ConcurrentLinkedQueue<Integer> messageQueue = new ConcurrentLinkedQueue<>();

    MessageConsumerRabbitMQImpl consumer = createConsumer(partitionCount);

    consumer.subscribe(subscriberId, ImmutableSet.of(destination), message ->
            messageQueue.add(Integer.parseInt(message.getPayload())));

    return new TestSubscription(consumer, messageQueue);
  }

  private MessageConsumerRabbitMQImpl createConsumer(int partitionCount) {
    MessageConsumerRabbitMQImpl messageConsumerRabbitMQ = new MessageConsumerRabbitMQImpl(rabbitMQURL,zkUrl, partitionCount);
    applicationContext.getAutowireCapableBeanFactory().autowireBean(messageConsumerRabbitMQ);
    return messageConsumerRabbitMQ;
  }

  private void sendMessages() {
    for (int i = 0; i < MESSAGE_COUNT; i++) {
      eventuateRabbitMQProducer.send(destination,
              String.valueOf(Math.random()),
              JSonMapper.toJson(new MessageImpl(String.valueOf(i),
                      Collections.singletonMap("ID", UUID.randomUUID().toString()))));
    }
  }
}