package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布前的“保存事务”（契约修订2 §3.3 步骤3）：独立 Bean + REQUIRES_NEW，提交后才进入检测/提交事务，
 * 因此检测不可用（503）时已保存的草稿不会被回滚。同一 requestId 的重试按内容哈希与版本幂等，不重复保存。
 */
@Component
public class ArticleDraftWriter {
    private final ArticleDraftRepository drafts;

    public ArticleDraftWriter(ArticleDraftRepository drafts) { this.drafts = drafts; }

    /** 返回本次提交应使用的草稿版本。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long saveForPublish(long userId, long id, String title, String summary, String body, long expectedVersion, String requestId, String hash) {
        ArticleDraft draft = drafts.lockById(id).filter(d -> d.getAuthorId() == userId)
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "未找到属于当前账号的文章。"));
        if (requestId.equals(draft.getLastRequestId())) {
            if (!hash.equals(draft.getLastRequestHash()))
                throw new ApiProblem(HttpStatus.CONFLICT, "同一个提交标识不能用于不同的内容，请重新发起发布。", "REQUEST_REUSED", null);
            if (draft.getLastRequestVersion() == null || draft.getVersion() != draft.getLastRequestVersion()) throw ArticleDraftService.conflict(draft);
            return draft.getVersion(); // 同一请求的重试：不重复保存，以已保存草稿进入提交
        }
        if (expectedVersion != draft.getVersion()) throw ArticleDraftService.conflict(draft);
        draft.applyContent(title, summary, body);
        draft.recordRequest(requestId, hash);
        drafts.saveAndFlush(draft);
        return draft.getVersion();
    }
}
