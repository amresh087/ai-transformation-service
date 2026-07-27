package com.retail.ai.kafka;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import com.retail.ai.dto.EdiDataEvent;
import com.retail.ai.edi.EdiToIdocRagAssembler;
import com.retail.ai.edi.MappingChunkProvider;
import com.retail.ai.service.CompletionService;
import com.retail.ai.service.IdocXmlStorageService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EdiDataEventListener {

    private static final Logger log = LoggerFactory.getLogger(EdiDataEventListener.class);

    private final MappingChunkProvider mappingChunkProvider;
    private final CompletionService completionService;
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
            EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(mappingChunkProvider,completionService);

            EdiToIdocRagAssembler.AssemblyResult assemblyResult = assembler.assemble(
                    event.getPayload(),
                    event.getTenant(),
                    event.getTransactionTypeCode());

            if (assemblyResult != null ) {
                idocXmlStorageService.storeGeneratedXml(assemblyResult.getFinalXml(), event);
            }
        } catch (Exception ex) {
            log.error("Failed to process EDI payload via RAG assembler for document {}", event.getDocumentId(), ex);
        }
    }

    // Local file fallback is no longer required for production storage.
    // The generated IDOC XML is stored in MinIO inbound by IdocXmlStorageService.
}
