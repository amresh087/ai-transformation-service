package com.retail.ai.service;

import com.retail.ai.dto.EdiDataEvent;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IdocXmlStorageServiceTest {

    @Test
    void shouldUploadGeneratedXmlToInboundFolderInMinio() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        IdocXmlStorageService storageService = new IdocXmlStorageService(minioClient);

        EdiDataEvent event = EdiDataEvent.builder()
                .documentId("doc-1")
                .documentName("orders.edi.txt")
                .build();

        storageService.storeGeneratedXml("<ORDERS05 />", event);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());

        PutObjectArgs args = captor.getValue();
        assertEquals("documents", args.bucket());
        assertEquals("inbound/doc-1.xml", args.object());
    }
}
