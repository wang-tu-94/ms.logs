package com.myproject.log;

import com.myproject.log.proto.LogMessage;
import com.myproject.log.proto.LogServiceGrpc;
import com.myproject.log.proto.LogSummary;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"app-logs"})
@DirtiesContext
class LogIngestorE2ETest {
    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Test
    void fullFlowTest() throws Exception {
        // 1. Configurer un consommateur manuel pour le test (plus fiable que @KafkaListener)
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer());
        Consumer<String, String> consumer = cf.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "app-logs");

        // 2. Client gRPC
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();

        LogServiceGrpc.LogServiceStub stub = LogServiceGrpc.newStub(channel);
        CountDownLatch latch = new CountDownLatch(1);

        // 3. Envoi du log
        StreamObserver<LogMessage> requestObserver = stub.sendLogs(new StreamObserver<>() {
            @Override public void onNext(LogSummary value) { latch.countDown(); }
            @Override public void onError(Throwable t) { t.printStackTrace(); }
            @Override public void onCompleted() {}
        });

        requestObserver.onNext(LogMessage.newBuilder()
                .setServiceName("E2E-SERVICE")
                .setContent("PAYLOAD-DE-TEST")
                .build());
        requestObserver.onCompleted();

        // 4. Attendre la confirmation gRPC
        boolean receivedByGrpc = latch.await(5, TimeUnit.SECONDS);
        assertThat(receivedByGrpc).as("L'ingestor n'a pas répondu au gRPC").isTrue();

        // 5. Lecture manuelle du topic Kafka
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));

        assertThat(records.count()).as("Aucun message dans Kafka").isGreaterThan(0);

        ConsumerRecord<String, String> singleRecord = records.iterator().next();
        assertThat(singleRecord.value()).contains("PAYLOAD-DE-TEST");

        consumer.close();
        channel.shutdown();
    }
}