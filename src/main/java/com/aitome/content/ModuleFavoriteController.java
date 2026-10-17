package com.aitome.content;

import com.aitome.access.Permission;
import com.aitome.access.RequirePermission;
import com.aitome.user.*;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/me/topic-builder/module-favorites")
@RequirePermission(Permission.AI_USE)
public class ModuleFavoriteController {
    private final UserRepository users; private final JdbcTemplate jdbc;
    private final ObjectMapper json; private final TopicDocumentValidator validator;
    public ModuleFavoriteController(UserRepository users,JdbcTemplate jdbc,ObjectMapper json,TopicDocumentValidator validator){this.users=users;this.jdbc=jdbc;this.json=json;this.validator=validator;}
    public record Favorite(String id,JsonNode block,Instant createdAt) {}
    public record Page(List<Favorite> items,int page,int size,boolean hasNext) {}
    public record Request(JsonNode block) {}
    @GetMapping public Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,HttpSession session) {
        long uid=user(session);int p=Math.max(0,Math.min(10000,page)),s=Math.max(1,Math.min(50,size));
        List<Favorite> rows=jdbc.query("select id,block,created_at from user_module_favorites where user_id=? order by created_at desc,id limit ? offset ?",(rs,n)->new Favorite(rs.getString(1),parse(rs.getString(2)),rs.getTimestamp(3).toInstant()),uid,s+1,(long)p*s);
        return new Page(rows.size()>s?rows.subList(0,s):rows,p,s,rows.size()>s);
    }
    @PostMapping @Transactional public Favorite create(@RequestBody Request request,HttpSession session) {
        long uid=user(session);validator.block(request.block(),false);
        // Select supported fields: unknown input is never persisted as hidden metadata.
        var block=json.createObjectNode();for(String field:List.of("id","kind","title","body","code"))if(request.block().has(field))block.set(field,request.block().get(field));
        jdbc.queryForObject("select id from user_accounts where id=? for update",Long.class,uid);
        if(jdbc.queryForObject("select count(*) from user_module_favorites where user_id=?",Integer.class,uid)>=1000)throw TopicDocumentValidator.bad("最多收藏 1000 个模块");
        String id=UUID.randomUUID().toString();Instant now=Instant.now();
        jdbc.update("insert into user_module_favorites(id,user_id,block,created_at) values(?,?,?,?)",id,uid,block.toString(),java.sql.Timestamp.from(now));
        return new Favorite(id,block,now);
    }
    @DeleteMapping("/{id}") @Transactional public Map<String,Boolean> delete(@PathVariable String id,HttpSession session) {
        if(jdbc.update("delete from user_module_favorites where id=? and user_id=?",id,user(session))==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"收藏不存在");return Map.of("deleted",true);
    }
    private long user(HttpSession session){return AuthController.requireUser(session,users).getId();}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR,"收藏无法读取");}}
}
