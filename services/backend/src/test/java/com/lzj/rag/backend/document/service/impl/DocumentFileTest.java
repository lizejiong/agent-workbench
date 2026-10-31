package com.lzj.rag.backend.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lzj.rag.backend.document.dao.entity.DocumentDO;
import com.lzj.rag.backend.document.dao.mapper.DocumentMapper;
import com.lzj.rag.backend.document.storage.DocumentStorage;
import com.lzj.rag.backend.document.upload.UploadOperations;
import com.lzj.rag.backend.infra.storage.config.StorageProperties;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.knowledgebase.service.internal.KnowledgeBaseAccess;
import com.lzj.rag.backend.parser.routing.ParserRegistry;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;

import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

class DocumentFileTest {
    private final DocumentMapper mapper = mock(DocumentMapper.class);
    private final KnowledgeBaseAccess bases = mock(KnowledgeBaseAccess.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final DocumentServiceImpl service =
            new DocumentServiceImpl(
                    mapper,
                    bases,
                    mock(UploadOperations.class),
                    storage,
                    mock(StorageProperties.class),
                    new ObjectMapper(),
                    mock(Validator.class),
                    mock(ParserRegistry.class));
    private final KnowledgeBasePrincipal actor =
            new KnowledgeBasePrincipal("alice", "unused", "tenant-a", "alice");
    private final UUID id = UUID.randomUUID();

    @Test
    void missingOrUnownedDocumentDoesNotAccessStorage() {
        assertThatThrownBy(() -> service.openFile(actor, id))
                .isInstanceOfSatisfying(
                        KnowledgeBaseException.class,
                        error -> assertThat(error.code()).isEqualTo("KB_DOCUMENT_NOT_FOUND"));
        verifyNoInteractions(bases, storage);
    }

    @Test
    void inaccessibleKnowledgeBaseDoesNotAccessStorage() {
        var document = document();
        when(mapper.selectOne(any())).thenReturn(document);
        when(bases.requireOwned(actor, document.getKbId()))
                .thenThrow(
                        new KnowledgeBaseException(
                                404, "KB_NOT_FOUND", "Knowledge base not found"));

        assertThatThrownBy(() -> service.openFile(actor, id))
                .isInstanceOfSatisfying(
                        KnowledgeBaseException.class,
                        error -> assertThat(error.code()).isEqualTo("KB_NOT_FOUND"));
        verifyNoInteractions(storage);
    }

    @Test
    void authorizedDownloadKeepsMetadataAndOpensContentAfterBothOwnershipChecks() throws Exception {
        var document = document();
        byte[] content = "source document".getBytes(StandardCharsets.UTF_8);
        document.setFileSize((long) content.length);
        when(mapper.selectOne(any())).thenReturn(document);
        var stream = new ByteArrayInputStream(content);
        when(storage.open(document.getObjectKey())).thenReturn(stream);

        var file = service.openFile(actor, id);
        assertThat(file.originalFilename()).isEqualTo("source.txt");
        assertThat(file.mimeType()).isEqualTo("text/plain");
        assertThat(file.fileSize()).isEqualTo(content.length);
        assertThat(file.content()).isSameAs(stream);
        try (var input = file.content()) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }
        var order = inOrder(mapper, bases, storage);
        order.verify(mapper).selectOne(any());
        order.verify(bases).requireOwned(actor, document.getKbId());
        order.verify(storage).open(document.getObjectKey());
    }

    private DocumentDO document() {
        var document = new DocumentDO();
        document.setId(id);
        document.setKbId(UUID.randomUUID());
        document.setOriginalFilename("source.txt");
        document.setMimeType("text/plain");
        document.setObjectKey("private/source");
        return document;
    }
}
