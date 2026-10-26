package com.aitome.content;

import com.aitome.access.*;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.HttpServletRequest;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

@RestController
public class CollectionCoverController {
    private final JdbcTemplate jdbc; private final AccessService access; private final CollectionPublicationService publications;
    @org.springframework.beans.factory.annotation.Autowired private com.aitome.review.ReviewAudit audit;
    public CollectionCoverController(JdbcTemplate jdbc,AccessService access,CollectionPublicationService publications) { this.jdbc=jdbc;this.access=access;this.publications=publications; }

    @PostMapping("/api/me/collections/{id}/cover") @RequirePermission(Permission.COLLECTION_MANAGE) @Transactional
    public Map<String,String> upload(@PathVariable long id,@RequestParam MultipartFile file,HttpServletRequest request) {
        AccessContext user=access.current(request);
        var rows=jdbc.queryForList("select owner_id,editor_type from community_collections where id=? for update",id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND,"专题不存在");
        if (!Objects.equals(((Number)rows.get(0).get("owner_id")).longValue(),user.userId()) || !"OUTLINE".equals(rows.get(0).get("editor_type"))) throw new ApiProblem(HttpStatus.FORBIDDEN,"只能上传本人传统专题封面");
        if (file.isEmpty() || file.getSize()>2*1024*1024) throw bad("封面需要2MB以内的JPEG或PNG");
        try {
            byte[] bytes=file.getBytes();
            boolean png=bytes.length>8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71;
            boolean jpeg=bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
            String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
            if ((!png&&!jpeg) || (png&&!filename.endsWith(".png")) || (jpeg&&!(filename.endsWith(".jpg")||filename.endsWith(".jpeg")))) throw bad("文件内容与JPEG/PNG格式不符");
            BufferedImage source;
            try (ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw bad("封面不是有效图片");
                ImageReader reader=readers.next();
                try {
                    reader.setInput(input,true,true);
                    int w=reader.getWidth(0),h=reader.getHeight(0);
                    if (w<16 || h<9 || w>4096 || h>4096) throw bad("封面尺寸须至少16×9且宽高不超过4096像素");
                    source=reader.read(0);
                } finally { reader.dispose(); }
            }
            int cropW=source.getWidth(),cropH=source.getHeight();
            if (cropW*9>cropH*16) cropW=cropH*16/9; else cropH=cropW*9/16;
            int scale=Math.max(1,Math.min(75,Math.min(cropW/16,cropH/9)));
            BufferedImage target=new BufferedImage(scale*16,scale*9,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=target.createGraphics();
            try { g.setColor(Color.WHITE);g.fillRect(0,0,target.getWidth(),target.getHeight());g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);int x=(source.getWidth()-cropW)/2,y=(source.getHeight()-cropH)/2;g.drawImage(source,0,0,target.getWidth(),target.getHeight(),x,y,x+cropW,y+cropH,null); } finally { g.dispose(); }
            ByteArrayOutputStream encoded=new ByteArrayOutputStream();ImageIO.write(target,"JPEG",encoded);
            String coverId=UUID.randomUUID().toString();
            jdbc.update("insert into collection_covers(id,collection_id,image_bytes,created_at,removed) values(?,?,?,current_timestamp,false)",coverId,id,encoded.toByteArray());
            return Map.of("coverId",coverId,"url","/api/collection-covers/"+coverId);
        } catch (IOException|IllegalArgumentException e) { throw bad("封面图片损坏或无法读取"); }
    }

    @GetMapping("/api/collection-covers/{coverId}") @PublicEndpoint("封面按作者/当前发布版/提交快照权限读取")
    public ResponseEntity<byte[]> read(@PathVariable String coverId,HttpServletRequest request) {
        var rows=jdbc.queryForList("select v.collection_id,v.image_bytes,v.removed,c.owner_id,c.moderation_state from collection_covers v join community_collections c on c.id=v.collection_id where v.id=?",coverId);
        if (rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("removed"))) throw missing();
        var row=rows.get(0);long id=((Number)row.get("collection_id")).longValue();AccessContext user=access.current(request);
        boolean owner=Objects.equals(((Number)row.get("owner_id")).longValue(),user.userId());
        boolean reviewer=user.has(Permission.CONTENT_REVIEW) && jdbc.queryForObject("select count(*) from review_submissions where content_type='COLLECTION' and snapshot_json like ?",Integer.class,"%"+coverId+"%")>0;
        if ("TAKEN_DOWN".equals(row.get("moderation_state"))) throw missing();
        if (!owner&&!reviewer) {
            CollectionMetadata metadata;
            try { metadata=publications.read(id,user).metadata(); } catch (ApiProblem e) { throw missing(); }
            if (metadata==null || !coverId.equals(metadata.coverId())) throw missing();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").eTag("\""+coverId+"\"").body((byte[])row.get("image_bytes"));
    }

    @DeleteMapping("/api/admin/collection-covers/{coverId}") @RequirePermission(Permission.CONTENT_REVIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(@PathVariable String coverId,@RequestBody Map<String,String> body,HttpServletRequest request) {
        String reason=body.getOrDefault("reason","").trim();if(reason.isEmpty()||reason.length()>200)throw bad("请填写1–200字移除原因");
        var rows=jdbc.queryForList("select v.collection_id,c.owner_id from collection_covers v join community_collections c on c.id=v.collection_id where v.id=?",coverId);if(rows.isEmpty())throw missing();
        var operator=access.current(request);var row=rows.get(0);String marker="%"+coverId+"%";
        if(!Objects.equals(((Number)row.get("owner_id")).longValue(),operator.userId()) && jdbc.queryForObject("select count(*) from review_submissions where content_type='COLLECTION' and snapshot_json like ?",Integer.class,marker)==0 && jdbc.queryForObject("select count(*) from collection_publications p join community_collections c on c.id=p.collection_id where c.status='PUBLISHED' and p.document like ?",Integer.class,marker)==0)throw missing();
        jdbc.update("update collection_covers set removed=true where id=?",coverId);
        audit.write(com.aitome.review.ReviewAudit.Row.of("TAKE_DOWN",operator.userId(),operator.displayName()).content(((Number)row.get("owner_id")).longValue(),"COLLECTION",((Number)row.get("collection_id")).longValue(),null,null,null).note(reason,"仅移除专题封面 "+coverId+"，专题正文保持"));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=86400000,initialDelay=86400000)
    @Transactional
    public void cleanup() {
        for (String id:jdbc.queryForList("select id from collection_covers where created_at<?",String.class,java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(86400)))) {
            String marker="%"+id+"%";
            if(jdbc.queryForList("select id from collection_covers where id=? for update",String.class,id).isEmpty())continue;
            if (jdbc.queryForObject("select count(*) from community_collections where wizard_metadata like ? or scheduled_document like ?",Integer.class,marker,marker)>0 || jdbc.queryForObject("select count(*) from collection_publications where document like ?",Integer.class,marker)>0 || jdbc.queryForObject("select count(*) from review_submissions where snapshot_json like ?",Integer.class,marker)>0) continue;
            jdbc.update("delete from collection_covers where id=?",id);
        }
    }
    private static ApiProblem bad(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST,message); }
    private static ApiProblem missing() { return new ApiProblem(HttpStatus.NOT_FOUND,"封面不存在"); }
}
