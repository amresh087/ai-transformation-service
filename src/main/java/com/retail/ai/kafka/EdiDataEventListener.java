package com.retail.ai.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import com.retail.ai.dto.EdiDataEvent;
import com.retail.ai.edi.DeterministicEdiToIdocMapper;
import com.retail.ai.edi.EdiToIdocRagAssembler;
import com.retail.ai.edi.MappingChunkProvider;
import com.retail.ai.edi.SegmentHierarchyRuleResolver;
import com.retail.ai.service.CompletionService;
import com.retail.ai.service.IdocXmlStorageService;
import com.retail.ai.service.OllamaService;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
public class EdiDataEventListener {

    private static final Logger log = LoggerFactory.getLogger(EdiDataEventListener.class);

    private final OllamaService ollamaService;
    private final MappingChunkProvider mappingChunkProvider;
    private final CompletionService completionService;
    private final DeterministicEdiToIdocMapper deterministicMapper;
    private final IdocXmlStorageService idocXmlStorageService;
    private final ExecutorService executorService;

    @KafkaListener(topics = "${app.kafka.topic.edi-data-event}", groupId = "${spring.kafka.consumer.group-id:ai-transformation-service-group}", containerFactory = "kafkaListenerContainerFactory")
    public void handleEdiDataEvent(EdiDataEvent event, Acknowledgment acknowledgment) {
        if (event == null || event.getPayload() == null) {
            log.warn("Empty EDI event or payload, skipping");
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            return;
        }

        log.info("Received EDI data event on topic 'edi-data-event': {}", event);

        if (acknowledgment != null) {
            try {
                acknowledgment.acknowledge();
            } catch (Exception e) {
                log.warn("Failed to acknowledge Kafka record for document {}", event.getDocumentId(), e);
            }
        }

        executorService.submit(() -> processEventAsync(event));
    }

    private void processEventAsync(EdiDataEvent event) {
        try {
            log.info("Processing EDI payload for document {}", event.getDocumentId());

            SegmentHierarchyRuleResolver resolver = new SegmentHierarchyRuleResolver(
                    "default",
                    event.getTenant(),
                    event.getTransactionTypeCode());

            EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(
                    resolver,
                    mappingChunkProvider,
                    completionService,
                    deterministicMapper);

            EdiToIdocRagAssembler.AssemblyResult assemblyResult = assembler.assemble(
                    event.getPayload(),
                    event.getTenant(),
                    event.getTransactionTypeCode());

            if (assemblyResult != null && assemblyResult.isHasContent()) {
                log.info("Assembled IDOC XML for document {} using {} segment results and {} unmapped segments",
                        event.getDocumentId(),
                        assemblyResult.getSegmentResults() != null ? assemblyResult.getSegmentResults().size() : 0,
                        assemblyResult.getUnmappedSegments() != null ? assemblyResult.getUnmappedSegments().size() : 0);
                idocXmlStorageService.storeGeneratedXml(assemblyResult.getFinalXml(), event);
            } else {
                log.warn("Assembler produced no successful mapped fragments for document {}, falling back to Ollama", event.getDocumentId());
                String fallbackResult = ollamaService.processUnified(event.getPayload(), "EDI_XML");
                if (fallbackResult != null && !fallbackResult.isBlank()) {
                    idocXmlStorageService.storeGeneratedXml(fallbackResult, event);
                } else {
                    log.error("Fallback Ollama processing also returned no result for document {}", event.getDocumentId());
                }
            }
        } catch (Exception ex) {
            log.error("Failed to process EDI payload via RAG assembler for document {}", event.getDocumentId(), ex);
        }
    }

    // Local file fallback is no longer required for production storage.
    // The generated IDOC XML is stored in MinIO inbound by IdocXmlStorageService.
}
