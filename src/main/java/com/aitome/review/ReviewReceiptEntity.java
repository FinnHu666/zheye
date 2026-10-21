package com.aitome.review;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "review_receipts")
@IdClass(ReviewReceiptEntity.Key.class)
public class ReviewReceiptEntity {
    @Id @Column(name = "state_id") private Long stateId;
    @Id @Column(name = "request_id", length = 64) private String requestId;
    @Column(name = "response_json", nullable = false, columnDefinition = "text") private String responseJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected ReviewReceiptEntity() {}

    public static class Key implements Serializable {
        private Long stateId;
        private String requestId;
        public Key() {}
        @Override public boolean equals(Object o) { return o instanceof Key k && Objects.equals(stateId, k.stateId) && Objects.equals(requestId, k.requestId); }
        @Override public int hashCode() { return Objects.hash(stateId, requestId); }
    }
}
