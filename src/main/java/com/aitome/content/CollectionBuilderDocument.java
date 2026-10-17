package com.aitome.content;

import jakarta.persistence.*;

@Entity
@Table(name="collection_builder_documents")
public class CollectionBuilderDocument {
    @Id @Column(name="collection_id") private Long collectionId;
    @Column(name="schema_version",nullable=false) private int schemaVersion;
    @Column(nullable=false,columnDefinition="text") private String document;
    protected CollectionBuilderDocument() {}
}
