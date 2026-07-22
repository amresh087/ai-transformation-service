package com.retail.ai.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.retail.ai.dto.EdiDataEvent;
import com.retail.ai.service.OllamaService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EdiDataEventListener {

    private static final Logger log = LoggerFactory.getLogger(EdiDataEventListener.class);

    private final OllamaService ollamaService;

    @KafkaListener(topics = "${app.kafka.topic.edi-data-event}", groupId = "${spring.kafka.consumer.group-id:ai-transformation-service-group}", containerFactory = "kafkaListenerContainerFactory")
    public void handleEdiDataEvent(EdiDataEvent event) {
        try {
            log.info("Received EDI data event on topic 'edi-data-event': {}", event);

            if (event == null || event.getPayload() == null) {
                log.warn("Empty EDI event or payload, skipping");
                return;
            }

            // Use OllamaService to process EDI payload into XML (sessionMode EDI_XML)
            try {
                System.out.println("====event.getPayload(): === " + event.getPayload());

                //String result = ollamaService.processUnified(event.getPayload(), "EDI_XML");
                //log.info("Ollama processed EDI payload. Result summary: {}", result);
            } catch (Exception ex) {
                log.error("Failed to process EDI payload via OllamaService", ex);
            }

        } catch (Exception e) {
            log.error("Error processing EDI data event: {}", event, e);
        }
    }
}
