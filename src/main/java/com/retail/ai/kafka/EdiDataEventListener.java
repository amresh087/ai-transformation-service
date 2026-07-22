package com.retail.ai.kafka;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

            try {
                log.info("Processing EDI payload for document {}", event.getDocumentId());
                String result = ollamaService.processUnified(event.getPayload(), "EDI_XML");

                if (result != null && !result.isBlank()) {
                    Path outputPath = writeXmlToFile(result, event);
                    log.info("Created IDOC XML file at {}", outputPath.toAbsolutePath());
                } else {
                    log.warn("Ollama returned an empty XML result for document {}", event.getDocumentId());
                }
            } catch (Exception ex) {
                log.error("Failed to process EDI payload via OllamaService", ex);
            }

        } catch (Exception e) {
            log.error("Error processing EDI data event: {}", event, e);
        }
    }

    private Path writeXmlToFile(String xmlContent, EdiDataEvent event) throws Exception {
        Path outputDir = Paths.get(System.getProperty("user.dir"), "target", "generated-idoc");
        Files.createDirectories(outputDir);

        String baseName = event.getDocumentId();
        if (baseName == null || baseName.isBlank()) {
            baseName = event.getDocumentName() != null ? event.getDocumentName() : "edi-event";
        }
        String sanitizedName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path outputPath = outputDir.resolve(sanitizedName + ".xml");

        Files.writeString(outputPath, xmlContent, StandardCharsets.UTF_8);
        return outputPath;
    }
}
