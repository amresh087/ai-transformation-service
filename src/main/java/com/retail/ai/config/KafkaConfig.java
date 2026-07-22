package com.retail.ai.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.util.backoff.FixedBackOff;

import com.retail.ai.dto.EdiDataEvent;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:ai-transformation-service-group}")
    private String groupId;

    @Bean
    public JsonDeserializer<EdiDataEvent> jsonDeserializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        JsonDeserializer<EdiDataEvent> deserializer = new JsonDeserializer<>(EdiDataEvent.class, mapper);
        deserializer.addTrustedPackages("*");
        deserializer.setUseTypeHeaders(false);
        return deserializer;
    }

    @Bean
    public ErrorHandlingDeserializer<EdiDataEvent> errorHandlingDeserializer(
            JsonDeserializer<EdiDataEvent> jsonDeserializer) {
        return new ErrorHandlingDeserializer<>(jsonDeserializer);
    }

    @Bean
    public ConsumerFactory<String, EdiDataEvent> consumerFactory(
            ErrorHandlingDeserializer<EdiDataEvent> errorHandlingDeserializer) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        return new DefaultKafkaConsumerFactory<>(configProps, new ErrorHandlingDeserializer<>(new StringDeserializer()), 
                errorHandlingDeserializer);
    }

    @Bean
    public DefaultErrorHandler errorHandler() {
        // Create a recovery handler that logs and skips bad records
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            log.error("Deserialization failed for record at partition {} offset {}. Error: {}",
                    record.partition(), record.offset(), exception.getMessage(), exception);
        };

        // Use fixed backoff with no retries for deserialization errors
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(0, 0));
        
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EdiDataEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, EdiDataEvent> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, EdiDataEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
