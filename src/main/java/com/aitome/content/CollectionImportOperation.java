package com.aitome.content;

import jakarta.persistence.*;

@Entity
@Table(name = "collection_import_operations")
public class CollectionImportOperation {
    @Id @Column(name = "operation_key", length = 160) private String operationKey;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "collection_id", nullable = false) private Long collectionId;
    protected CollectionImportOperation() {}
}
