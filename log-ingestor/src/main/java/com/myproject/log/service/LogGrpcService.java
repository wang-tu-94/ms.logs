package com.myproject.log.service;

import com.myproject.log.proto.LogMessage;
import com.myproject.log.proto.LogServiceGrpc;
import com.myproject.log.proto.LogSummary;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@GrpcService
@Service
public class LogGrpcService extends LogServiceGrpc.LogServiceImplBase {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topicName;

    public LogGrpcService(KafkaTemplate<String, String> kafkaTemplate,
                          @Value("${app.kafka.topic-name}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    @Override
    public StreamObserver<LogMessage> sendLogs(StreamObserver<LogSummary> responseObserver) {
        AtomicInteger counter = new AtomicInteger(0);

        return new StreamObserver<>() {
            @Override
            public void onNext(LogMessage logMessage) {
                // On pousse vers Kafka
                kafkaTemplate.send("app-logs", logMessage.getServiceName(), logMessage.getContent());
                counter.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {
                log.error("Erreur lors de la réception du flux gRPC", t);
            }

            @Override
            public void onCompleted() {
                LogSummary summary = LogSummary.newBuilder()
                        .setLogsProcessed(counter.get())
                        .setStatus("SUCCESS")
                        .build();

                responseObserver.onNext(summary);
                responseObserver.onCompleted();
                log.info("Flux terminé. {} logs traités.", counter.get());
            }
        };
    }
}