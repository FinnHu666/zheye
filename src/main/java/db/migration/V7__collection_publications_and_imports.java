package db.migration;

import com.aitome.content.CollectionPublicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Java migration serializes the same versioned DTO used by publication reads. */
public class V7__collection_publications_and_imports extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2026100201; }
    @Override public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        jdbc.execute("create table collection_publications (collection_id bigint primary key references community_collections(id) on delete cascade,name varchar(80) not null,description varchar(260) not null,owner_name varchar(40) not null,item_count integer not null,schema_version integer not null,document text not null,updated_at timestamp with time zone not null)");
        jdbc.execute("create index idx_publication_updated on collection_publications(updated_at desc,collection_id desc)");
        jdbc.execute("create table collection_import_operations (operation_key varchar(160) primary key,user_id bigint not null references user_accounts(id),request_hash varchar(64) not null,collection_id bigint not null references community_collections(id))");
        CollectionPublicationService service = new CollectionPublicationService(jdbc, new ObjectMapper().findAndRegisterModules());
        jdbc.queryForList("select id from community_collections where status='PUBLISHED'", Long.class).forEach(service::publishSnapshot);
    }
}
