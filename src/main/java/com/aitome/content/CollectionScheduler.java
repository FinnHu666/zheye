package com.aitome.content;

import com.aitome.access.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static com.aitome.content.CollectionWorkspaceController.*;

@Component @EnableScheduling
public class CollectionScheduler {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final CollectionPublicationService publications;
    private final AccessService access; private final TransactionTemplate tx;
    @Value("${finns.collections.scheduling-enabled:false}") private boolean enabled;
    public CollectionScheduler(JdbcTemplate jdbc,ObjectMapper json,CollectionPublicationService publications,AccessService access,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.publications=publications;this.access=access;this.tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void tick() {
        if (!enabled) return;
        tx.executeWithoutResult(status -> {
            List<Map<String,Object>> rows=jdbc.queryForList("select id,owner_id,scheduled_document,scheduled_visibility,moderation_state,status from community_collections where scheduled_ready=true and scheduled_at<=? order by scheduled_at limit 50 for update skip locked",Timestamp.from(Instant.now()));
            for (var row:rows) {
                long id=((Number)row.get("id")).longValue();
                AccessContext author=access.forUser(((Number)row.get("owner_id")).longValue());
                if (author==null || !author.active() || !author.has(Permission.COLLECTION_MANAGE) || ("RESTRICTED".equals(row.get("scheduled_visibility"))&&!author.has(Permission.CONTENT_RESTRICTED)) || "TAKEN_DOWN".equals(row.get("moderation_state"))) { publications.clearSchedule(id);continue; }
                try {
                    PublicOutline outline=json.readValue((String)row.get("scheduled_document"),PublicOutline.class);
                    publications.assertReferences(outline);
                    publications.publishFrozen(id,outline,(String)row.get("scheduled_visibility"));
                    jdbc.update("update community_collections set status='PUBLISHED',published_at=coalesce(published_at,current_timestamp),updated_at=current_timestamp,revision=revision+1 where id=?",id);
                    List<Long> flows=jdbc.queryForList("select f.id from review_flows f join review_content_state s on s.id=f.state_id where s.content_type='COLLECTION' and s.target_id=? order by f.id desc limit 1",Long.class,id);
                    if(!flows.isEmpty()) jdbc.update("insert into review_flow_events(flow_id,stage,title,note,detail,occurred_at) values(?,'RESULT','定时已公开','发布已通过检测的提交版本','',current_timestamp)",flows.get(0));
                    publications.clearSchedule(id);
                } catch (com.aitome.common.ApiExceptionHandler.ApiProblem e) { publications.clearSchedule(id); }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot read scheduled collection",e); }
            }
        });
    }
}
