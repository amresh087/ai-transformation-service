package com.retail.ai.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.retail.ai.dto.EdiDataEvent;
import com.retail.ai.edi.EdiToIdocRagAssembler;
import com.retail.ai.edi.MappingChunkProvider;
import com.retail.ai.edi.SegmentHierarchyRuleResolver;
import com.retail.ai.service.CompletionService;
import com.retail.ai.service.IdocXmlStorageService;
import com.retail.ai.service.OllamaService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EdiDataEventListener {

    private static final Logger log = LoggerFactory.getLogger(EdiDataEventListener.class);

    private final OllamaService ollamaService;
    private final MappingChunkProvider mappingChunkProvider;
    private final CompletionService completionService;
    private final IdocXmlStorageService idocXmlStorageService;

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
                
                SegmentHierarchyRuleResolver resolver = new SegmentHierarchyRuleResolver(
                        "default",
                        event.getTenant(),
                        event.getTransactionTypeCode());

                EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(
                        resolver,
                        mappingChunkProvider,
                        completionService);

                EdiToIdocRagAssembler.AssemblyResult assemblyResult = assembler.assemble(
                        event.getPayload(),
                        event.getTenant(),
                        event.getTransactionTypeCode());

                String result = assemblyResult != null ? assemblyResult.getFinalXml() : null;
                if (result != null && !result.isBlank()) {
                    log.info("Assembled IDOC XML for document {} using {} segment results and {} unmapped segments",
                            event.getDocumentId(),
                            assemblyResult.getSegmentResults() != null ? assemblyResult.getSegmentResults().size() : 0,
                            assemblyResult.getUnmappedSegments() != null ? assemblyResult.getUnmappedSegments().size() : 0);
                    idocXmlStorageService.storeGeneratedXml(result, event);
                } else {
                    log.warn("Assembler returned an empty XML result for document {}, falling back to Ollama", event.getDocumentId());
                    String fallbackResult = ollamaService.processUnified(event.getPayload(), "EDI_XML");
                    if (fallbackResult != null && !fallbackResult.isBlank()) {
                        idocXmlStorageService.storeGeneratedXml(fallbackResult, event);
                    }
                }
            } catch (Exception ex) {
                log.error("Failed to process EDI payload via RAG assembler", ex);
            }

        } catch (Exception e) {
            log.error("Error processing EDI data event: {}", event, e);
        }
    }

    // Local file fallback is no longer required for production storage.
    // The generated IDOC XML is stored in MinIO inbound by IdocXmlStorageService.
}
