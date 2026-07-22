package com.retail.ai.service;

import com.retail.ai.dto.EdiDataEvent;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class IdocXmlStorageService {

    private static final Logger log = LoggerFactory.getLogger(IdocXmlStorageService.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket-name:documents}")
    private String bucketName;

    public void storeGeneratedXml(String xmlContent, EdiDataEvent event) {
        try {
            String resolvedBucketName = StringUtils.hasText(bucketName) ? bucketName : "documents";
            String objectName = buildInboundObjectName(event);

            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(resolvedBucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(resolvedBucketName).build());
            }

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(resolvedBucketName)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)), xmlContent.length(), -1)
                    .contentType("application/xml")
                    .build());
            log.info("Stored generated IDOC XML in MinIO at {}/{}", resolvedBucketName, objectName);
        } catch (Exception ex) {
            log.error("Failed to store generated IDOC XML for document {} in MinIO", event != null ? event.getDocumentId() : null, ex);
        }
    }

    private String buildInboundObjectName(EdiDataEvent event) {
        String documentId = event != null && event.getDocumentId() != null && !event.getDocumentId().isBlank()
                ? event.getDocumentId()
                : "edi-event";
        return "inbound/" + documentId + ".xml";
    }
}
