package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import static com.aitome.content.CollectionWorkspaceController.*;

@RestController
public class CollectionImportController {
    private final CollectionWorkspaceController workspace;
    private final CollectionRepository collections;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CollectionImportController(CollectionWorkspaceController workspace, CollectionRepository collections, UserRepository users, JdbcTemplate jdbc, ObjectMapper json) {
        this.workspace=workspace; this.collections=collections; this.users=users; this.jdbc=jdbc; this.json=json;
    }

    @GetMapping("/api/me/collection-templates")
    public List<CollectionTemplates.Template> templates(HttpSession session) {
        AuthController.requireUser(session, users); return CollectionTemplates.ALL;
    }

    public record ParseRequest(String text, boolean preserveWhole) {}
    public record ParseResult(SaveDraftRequest document, List<String> warnings, boolean canSave) {}
    public record CommitRequest(String idempotencyKey, SaveDraftRequest document) {}
    public record DuplicateRequest(boolean structureOnly) {}
    private static final Pattern HEADING = Pattern.compile("^(?:#{1,6}\\s+(.+)|(?:第[一二三四五六七八九十百0-9]+[章节步][：:.、\\s]*)(.+)|(?:[0-9]+[.、）)]\\s*)(.+))$");

    @PostMapping("/api/me/collection-imports/preview")
    public ParseResult parse(@RequestBody ParseRequest request, HttpSession session) {
        AuthController.requireUser(session, users);
        String text = request.text();
        if (text == null || text.isBlank()) throw bad("先粘贴或导入文字");
        if (text.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw bad("单次资料最多 1 MiB，请分批导入");
        if (text.indexOf('\uFFFD') >= 0 || text.indexOf('\u0000') >= 0) throw bad("资料包含无法识别的字符，请使用 UTF-8 文字文件");
        List<String> warnings = new ArrayList<>();
        if (text.contains("![")) warnings.add("图片仅保留文字引用，未下载图片。");
        if (text.lines().anyMatch(l -> l.contains("|") || l.contains("<"))) warnings.add("表格或 HTML 保留为文字，不执行或转换。");
        List<SectionInput> sections = new ArrayList<>();
        String title = "导入的经验";
        String sectionTitle = "资料内容";
        StringBuilder body = new StringBuilder();
        boolean fenced = false, recognized = false;
        int bodyLines = 0;
        for (String line : text.replace("\r\n", "\n").replace('\r','\n').split("\n", -1)) {
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) fenced = !fenced;
            Matcher matcher = HEADING.matcher(line);
            if (!request.preserveWhole() && !fenced && matcher.matches()) {
                if (body.length() > 0 || recognized) sections.add(section(sectionTitle, body.toString()));
                String heading = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                if (!recognized) title = heading.substring(0, Math.min(80, heading.length()));
                sectionTitle = heading.substring(0, Math.min(80, heading.length()));
                body.setLength(0); bodyLines = 0;
                if (heading.length() > 80) { body.append(line); bodyLines++; }
                recognized = true;
            } else { if (bodyLines++ > 0) body.append('\n'); body.append(line); }
        }
        if (body.length() > 0 || recognized) sections.add(section(sectionTitle, body.toString()));
        int count = sections.stream().mapToInt(s -> s.entries().size()).sum();
        if (!recognized) warnings.add("没有可靠标题，按原顺序保留内容；可自行修改目录。");
        if (count > sections.size()) warnings.add("长文已拆为连续笔记，内容顺序保留。");
        boolean canSave = sections.size() <= 50 && count <= 500;
        if (!canSave) warnings.add("超过 50 章或 500 条，请拆成多个专题；原文仍保留在输入区。");
        return new ParseResult(new SaveDraftRequest(0, title, "", CommunityCollection.Format.CUSTOM, "", "", sections), warnings, canSave);
    }

    private SectionInput section(String title, String body) {
        List<EntryInput> entries = new ArrayList<>();
        // Keep whitespace inside chunks; never truncate content to fit a field.
        for (int start=0; start<body.length();) {
            int end = Math.min(start+4000, body.length());
            if (end < body.length() && Character.isHighSurrogate(body.charAt(end-1))) end--;
            String chunk = body.substring(start,end);
            entries.add(new EntryInput(null, CollectionEntry.Kind.NOTE, "笔记 " + (entries.size()+1), "", chunk, null, ""));
            start=end;
        }
        return new SectionInput(null,title,"",entries);
    }

    @PostMapping("/api/me/collection-imports/commit")
    @ResponseStatus(HttpStatus.CREATED) @Transactional
    public DraftDocument commit(@RequestBody CommitRequest request, HttpSession session) {
        UserAccount user = AuthController.requireUser(session,users);
        String key = begin(user.getId(), "create", request);
        DraftDocument existing = existing(key, request, user.getId());
        if (existing != null) return existing;
        workspace.validateDocument(request.document(),user.getId());
        SaveDraftRequest doc=request.document();
        CommunityCollection saved=collections.saveAndFlush(new CommunityCollection(doc.name(),doc.description()==null?"":doc.description(),user.getDisplayName(),user.getId(),doc.format(),CommunityCollection.Status.DRAFT,doc.audience()==null?"":doc.audience(),doc.goal()==null?"":doc.goal()));
        DraftDocument result=workspace.saveDocument(saved.getId(),withRevision(doc,0),user.getId());
        finish(key,request,user.getId(),result.id()); return result;
    }

    @PostMapping("/api/me/collections/{id}/imports") @Transactional
    public DraftDocument append(@PathVariable long id, @RequestBody CommitRequest request, HttpSession session) {
        UserAccount user=AuthController.requireUser(session,users);
        workspace.draft(id,user.getId());
        String key=begin(user.getId(),"append:"+id,request);
        DraftDocument existing=existing(key,request,user.getId()); if(existing!=null)return existing;
        DraftDocument current=workspace.draft(id,user.getId());
        if(request.document()==null)throw bad("导入文档不能为空");
        List<SectionInput> sections=new ArrayList<>(input(current).sections());
        if(request.document().sections()==null)throw bad("导入章节不能为空");
        sections.addAll(request.document().sections());
        SaveDraftRequest merged=new SaveDraftRequest(request.document().revision(),current.name(),current.description(),current.format(),current.audience(),current.goal(),sections);
        DraftDocument result=workspace.saveDocument(id,merged,user.getId());
        finish(key,request,user.getId(),id);return result;
    }

    @PostMapping("/api/me/collections/{id}/duplicate") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public DraftDocument duplicate(@PathVariable long id,@RequestBody DuplicateRequest request,HttpSession session) {
        UserAccount user=AuthController.requireUser(session,users);
        DraftDocument original=workspace.draft(id,user.getId());
        SaveDraftRequest input=input(original);
        List<SectionInput> sections=request.structureOnly()?input.sections().stream().map(s->new SectionInput(null,s.title(),"",List.<EntryInput>of())).toList():input.sections();
        CommunityCollection saved=collections.saveAndFlush(new CommunityCollection(original.name().substring(0,Math.min(76,original.name().length()))+"（副本）",request.structureOnly()?"":original.description(),user.getDisplayName(),user.getId(),original.format(),CommunityCollection.Status.DRAFT,request.structureOnly()?"":original.audience(),request.structureOnly()?"":original.goal()));
        return workspace.saveDocument(saved.getId(),new SaveDraftRequest(0,saved.getName(),saved.getDescription(),saved.getFormat(),saved.getAudience(),saved.getGoal(),sections),user.getId());
    }

    public static SaveDraftRequest input(DraftDocument d) {
        return new SaveDraftRequest(d.revision(),d.name(),d.description(),d.format(),d.audience(),d.goal(),d.sections().stream().map(s->new SectionInput(null,s.title(),s.description(),s.entries().stream().map(e->new EntryInput(null,e.kind(),e.title(),e.annotation(),e.body(),e.postId(),e.externalUrl())).toList())).toList());
    }
    private SaveDraftRequest withRevision(SaveDraftRequest d,long revision){return new SaveDraftRequest(revision,d.name(),d.description(),d.format(),d.audience(),d.goal(),d.sections());}
    private String begin(long userId,String action,CommitRequest request){
        if(request.idempotencyKey()==null || !request.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))throw bad("缺少有效的提交标识");
        jdbc.queryForObject("select id from user_accounts where id=? for update",Long.class,userId);
        return userId+":"+action+":"+request.idempotencyKey();
    }
    private DraftDocument existing(String key,CommitRequest request,long userId){
        List<Map<String,Object>> rows=jdbc.queryForList("select request_hash,collection_id from collection_import_operations where operation_key=?",key);
        if(rows.isEmpty())return null;
        if(!rows.get(0).get("request_hash").equals(hash(request)))throw new ApiProblem(HttpStatus.CONFLICT,"这次提交的内容已变化，请重新确认导入");
        return workspace.draft(((Number)rows.get(0).get("collection_id")).longValue(),userId);
    }
    private void finish(String key,CommitRequest r,long userId,long id){jdbc.update("insert into collection_import_operations(operation_key,user_id,request_hash,collection_id) values(?,?,?,?)",key,userId,hash(r),id);}
    private String hash(CommitRequest r){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(r.document())));}catch(Exception e){throw new IllegalStateException(e);}}
    private ApiProblem bad(String message){return new ApiProblem(HttpStatus.BAD_REQUEST,message);}
}
