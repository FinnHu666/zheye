package com.aitome.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit, one-off importer. Enable with postgres profile and FINNS_H2_MIGRATION_ENABLED=true. */
@Component
@Profile("postgres")
@ConditionalOnProperty(prefix = "finns.migration.h2", name = "enabled", havingValue = "true")
public class H2ToPostgresMigration implements CommandLineRunner {
    private static final String BATCH_KEY = "h2-aitome-v1";
    private static final List<String> TABLES = List.of("user_accounts", "content_posts", "comments", "community_collections", "vps_recommendations");
    private final JdbcTemplate target;
    private final TransactionTemplate transaction;
    private final String sourceUrl;
    private final Path sourceFile;
    private final boolean dryRun;

    public H2ToPostgresMigration(JdbcTemplate target, PlatformTransactionManager transactionManager,
                                 org.springframework.core.env.Environment environment) {
        this.target = target;
        this.transaction = new TransactionTemplate(transactionManager);
        this.sourceUrl = environment.getRequiredProperty("finns.migration.h2.url");
        this.sourceFile = Path.of(environment.getRequiredProperty("finns.migration.h2.source-file")).toAbsolutePath().normalize();
        this.dryRun = environment.getProperty("finns.migration.h2.dry-run", Boolean.class, false);
    }

    @Override
    public void run(String... args) throws Exception {
        if (!Files.isRegularFile(sourceFile)) {
            System.out.println("H2 import skipped: source database file not found at " + sourceFile);
            return;
        }
        Integer done = target.queryForObject("select count(*) from data_migration_batches where batch_key=?", Integer.class, BATCH_KEY);
        if (!dryRun && done != null && done > 0) {
            System.out.println("H2 import skipped: audited batch " + BATCH_KEY + " already exists.");
            return;
        }
        Class.forName("org.h2.Driver");
        try (Connection source = DriverManager.getConnection(sourceUrl, "sa", "")) {
            source.setReadOnly(true);
            if (dryRun) {
                reportDryRun(source);
                return;
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String table : TABLES) counts.put(table, countTable(source, table));
            List<Long> unresolvedOwnerIds = transaction.execute(status -> migrate(source, counts));
            System.out.printf("H2 import complete: batch=%s counts=%s unresolvedOwnerCollectionIds=%s%n", BATCH_KEY, counts, unresolvedOwnerIds);
        }
    }

    private void reportDryRun(Connection source) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) counts.put(table, countTable(source, table));
        Set<String> emails = new HashSet<>();
        List<String> emailCollisions = new ArrayList<>();
        each(source, "select email from user_accounts order by id", rs -> {
            String email = rs.getString(1).toLowerCase(Locale.ROOT);
            if (!emails.add(email)) emailCollisions.add(email);
        });
        Set<String> ownerNames = new HashSet<>();
        Set<String> duplicateOwnerNames = new HashSet<>();
        each(source, "select display_name from user_accounts", rs -> {
            String name = rs.getString(1);
            if (!ownerNames.add(name)) duplicateOwnerNames.add(name);
        });
        List<Long> unresolvedOwners = new ArrayList<>();
        each(source, "select id,owner_name from community_collections order by id", rs -> {
            if (!ownerNames.contains(rs.getString("owner_name")) || duplicateOwnerNames.contains(rs.getString("owner_name"))) unresolvedOwners.add(rs.getLong("id"));
        });
        long orphanPosts = countSql(source, "select count(*) from content_posts p left join user_accounts u on u.id=p.author_id where u.id is null");
        long orphanComments = countSql(source, "select count(*) from comments c left join content_posts p on p.id=c.post_id left join user_accounts u on u.id=c.author_id where p.id is null or u.id is null");
        Integer applied = target.queryForObject("select count(*) from data_migration_batches where batch_key=?", Integer.class, BATCH_KEY);
        Map<String, Long> targetCounts = new LinkedHashMap<>();
        for (String table : TABLES) targetCounts.put(table, target.queryForObject("select count(*) from " + table, Long.class));
        if (!emailCollisions.isEmpty()) throw new IllegalStateException("H2 dry-run found case-insensitive email collisions: " + emailCollisions);
        if (orphanPosts > 0 || orphanComments > 0) throw new IllegalStateException("H2 dry-run found broken source references: orphanPosts=" + orphanPosts + ", orphanComments=" + orphanComments);
        boolean alreadyApplied = applied != null && applied > 0;
        if (alreadyApplied && !counts.equals(targetCounts)) throw new IllegalStateException("H2 source row counts no longer match the audited PostgreSQL migration batch.");
        if (!alreadyApplied && targetCounts.values().stream().anyMatch(count -> count > 0)) throw new IllegalStateException("PostgreSQL contains application rows but no matching H2 migration audit batch; refusing an unsafe import.");
        System.out.printf("H2 DRY RUN (read-only): source=%s sourceCounts=%s targetCounts=%s unresolvedOwnerCollectionIds=%s targetAlreadyMigrated=%s%n",
                sourceFile, counts, targetCounts, unresolvedOwners, alreadyApplied);
        System.out.println("No application data rows were modified; Flyway may initialize schema when migrations are pending.");
    }

    private List<Long> migrate(Connection source, Map<String, Long> counts) {
        Integer done = target.queryForObject("select count(*) from data_migration_batches where batch_key=?", Integer.class, BATCH_KEY);
        if (done != null && done > 0) return List.of();

        Map<Long, String> userIds = new HashMap<>();
        Set<String> existingEmails = new HashSet<>();
        Map<String, List<Long>> userIdsByName = new HashMap<>();
        each(source, "select id,email,password_hash,display_name,created_at from user_accounts order by id", rs -> {
            long id = rs.getLong("id");
            String displayName = rs.getString("display_name");
            String email = rs.getString("email").toLowerCase(Locale.ROOT);
            if (!existingEmails.add(email)) throw new IllegalStateException("Duplicate case-insensitive user email in H2; refusing ambiguous migration.");
            userIds.put(id, email);
            userIdsByName.computeIfAbsent(displayName, ignored -> new ArrayList<>()).add(id);
            target.update("insert into user_accounts(id,email,password_hash,display_name,created_at,bio) values(?,?,?,?,?,?)",
                    id, email, rs.getString("password_hash"), displayName, timestamp(rs, "created_at"), "");
        });

        each(source, "select id,type,title,summary,body,author_id,author_name,created_at,likes from content_posts order by id", rs ->
                target.update("insert into content_posts(id,type,title,summary,body,author_id,author_name,created_at,likes) values(?,?,?,?,?,?,?,?,?)",
                        rs.getLong("id"), rs.getString("type"), rs.getString("title"), rs.getString("summary"), rs.getString("body"),
                        rs.getLong("author_id"), rs.getString("author_name"), timestamp(rs, "created_at"), rs.getInt("likes")));

        each(source, "select id,post_id,author_id,author_name,body,created_at from comments order by id", rs ->
                target.update("insert into comments(id,post_id,author_id,author_name,body,created_at) values(?,?,?,?,?,?)",
                        rs.getLong("id"), rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("author_name"), rs.getString("body"), timestamp(rs, "created_at")));

        List<Long> unresolved = new ArrayList<>();
        each(source, "select id,name,description,owner_name,item_count,created_at from community_collections order by id", rs -> {
            long id = rs.getLong("id");
            String ownerName = rs.getString("owner_name");
            List<Long> candidates = userIdsByName.getOrDefault(ownerName, List.of());
            Long ownerId = candidates.size() == 1 ? candidates.get(0) : null;
            if (ownerId == null) unresolved.add(id);
            Timestamp createdAt = timestamp(rs, "created_at");
            target.update("insert into community_collections(id,name,description,owner_name,item_count,created_at,owner_id,updated_at) values(?,?,?,?,?,?,?,?)",
                    id, rs.getString("name"), rs.getString("description"), ownerName, rs.getInt("item_count"), createdAt, ownerId, createdAt);
        });

        each(source, "select id,provider,plan_name,region,cpu,memory_gb,storage_gb,monthly_price,score,description,author_name,created_at from vps_recommendations order by id", rs ->
                target.update("insert into vps_recommendations(id,provider,plan_name,region,cpu,memory_gb,storage_gb,monthly_price,score,description,author_name,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                        rs.getLong("id"), rs.getString("provider"), rs.getString("plan_name"), rs.getString("region"), rs.getInt("cpu"), rs.getInt("memory_gb"),
                        rs.getInt("storage_gb"), rs.getBigDecimal("monthly_price"), rs.getDouble("score"), rs.getString("description"), rs.getString("author_name"), timestamp(rs, "created_at")));

        for (String table : TABLES) target.queryForObject("select setval(pg_get_serial_sequence(?, 'id'), coalesce(max(id),1), max(id) is not null) from " + table,
                Object.class, table);
        String countsJson = toJson(counts);
        String unresolvedJson = toJson(unresolved);
        target.update("insert into data_migration_batches(batch_key,source_label,completed_at,row_counts,unresolved_owner_collection_ids) values(?,?,current_timestamp,?::jsonb,?::jsonb)",
                BATCH_KEY, sourceFile.toString(), countsJson, unresolvedJson);
        return unresolved;
    }

    private long countTable(Connection source, String table) throws SQLException {
        return countSql(source, "select count(*) from " + table);
    }

    private long countSql(Connection source, String sql) throws SQLException {
        try (Statement statement = source.createStatement(); ResultSet rs = statement.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private void each(Connection source, String sql, SqlRowConsumer consumer) {
        try (Statement statement = source.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) consumer.accept(rs);
        } catch (SQLException e) { throw new IllegalStateException("H2 import query failed: " + sql, e); }
    }

    private Timestamp timestamp(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof Timestamp ts) return ts;
        if (value instanceof OffsetDateTime odt) return Timestamp.from(odt.toInstant());
        if (value instanceof Instant instant) return Timestamp.from(instant);
        if (value instanceof LocalDateTime local) return Timestamp.from(local.toInstant(ZoneOffset.UTC));
        if (value instanceof ZonedDateTime zoned) return Timestamp.from(zoned.toInstant());
        throw new SQLException("Unsupported timestamp value for " + column + ": " + value);
    }

    private String toJson(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot encode migration audit JSON", e); }
    }

    @FunctionalInterface private interface SqlRowConsumer { void accept(ResultSet row) throws SQLException; }
}
