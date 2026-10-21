package com.aitome.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/** 审核记录（独立于 access_audit_log，避免把全站审计权限给审批人员）。拒绝/失败记录在独立事务提交，业务回滚不吞掉记录。 */
@Component
public class ReviewAudit {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public ReviewAudit(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Row(String type, String outcome, Long operatorId, String operatorName, Long authorId, String contentType, Long contentId,
                      Long submissionId, Long snapshotVersion, String triggers, String phrase, Boolean ruleEnabled, String note, String resultMessage) {
        public static Row of(String type, Long operatorId, String operatorName) {
            return new Row(type, "SUCCESS", operatorId, operatorName, null, null, null, null, null, null, null, null, null, null);
        }
        public Row content(Long authorId, String contentType, Long contentId, Long submissionId, Long snapshotVersion, String triggers) {
            return new Row(type, outcome, operatorId, operatorName, authorId, contentType, contentId, submissionId, snapshotVersion, triggers, phrase, ruleEnabled, note, resultMessage);
        }
        public Row rule(String phrase, boolean enabled) {
            return new Row(type, outcome, operatorId, operatorName, authorId, contentType, contentId, submissionId, snapshotVersion, triggers, phrase, enabled, note, resultMessage);
        }
        public Row note(String note, String resultMessage) {
            return new Row(type, outcome, operatorId, operatorName, authorId, contentType, contentId, submissionId, snapshotVersion, triggers, phrase, ruleEnabled, ReviewText.cut(note, 200), ReviewText.cut(resultMessage, 200));
        }
        public Row outcome(String outcome) {
            return new Row(type, outcome, operatorId, operatorName, authorId, contentType, contentId, submissionId, snapshotVersion, triggers, phrase, ruleEnabled, note, resultMessage);
        }
    }

    /** 与业务变更同一事务。 */
    public void write(Row row) { insert(row); }

    /** 独立事务：用于拒绝/失败记录。 */
    public void writeIndependent(Row row) { independent.executeWithoutResult(status -> insert(row)); }

    private void insert(Row r) {
        jdbc.update("insert into review_audit_log(type,outcome,operator_id,operator_name,author_id,content_type,content_id,submission_id,snapshot_version,triggers,phrase,rule_enabled,note,result_message,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.type(), r.outcome(), r.operatorId(), ReviewText.cut(r.operatorName(), 40), r.authorId(), r.contentType(), r.contentId(), r.submissionId(), r.snapshotVersion(),
                r.triggers(), r.phrase(), r.ruleEnabled(), r.note(), r.resultMessage(), Timestamp.from(Instant.now()));
    }
}
