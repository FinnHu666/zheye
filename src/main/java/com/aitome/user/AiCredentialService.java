package com.aitome.user;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Service
public class AiCredentialService {
    private final JdbcTemplate jdbc;
    private final SecretKeySpec master;
    private final SecureRandom random=new SecureRandom();
    public AiCredentialService(JdbcTemplate jdbc,@Value("${finns.ai.credential-secret:${FINNS_AI_CREDENTIAL_SECRET:}}") String secret) {
        this.jdbc=jdbc;
        SecretKeySpec key=null;
        try { byte[] bytes=Base64.getDecoder().decode(secret); if(bytes.length==32)key=new SecretKeySpec(bytes,"AES"); }
        catch(IllegalArgumentException ignored) { }
        this.master=key;
    }
    public record Status(boolean configured,String lastFour,boolean storageAvailable) {}
    public Status status(long userId) {
        List<String> tails=jdbc.query("select last_four from user_ai_credentials where user_id=?",(rs,n)->rs.getString(1),userId);
        return new Status(!tails.isEmpty(),tails.isEmpty()?"":tails.get(0),master!=null);
    }
    public boolean available(long userId){return master!=null&&status(userId).configured();}
    @Transactional
    public Status save(long userId,String value) {
        requireMaster();
        if(value==null||!value.matches("[!-~]{1,512}"))throw new ApiProblem(HttpStatus.BAD_REQUEST,"Key 需要 1–512 个可见英文字符，请检查空格与换行");
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        String encrypted=Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE,userId,nonce,value.getBytes(StandardCharsets.UTF_8)));
        jdbc.queryForObject("select id from user_accounts where id=? for update",Long.class,userId);
        jdbc.update("delete from user_ai_credentials where user_id=?",userId);
        jdbc.update("insert into user_ai_credentials(user_id,ciphertext,nonce,last_four,updated_at) values(?,?,?,?,current_timestamp)",userId,encrypted,Base64.getEncoder().encodeToString(nonce),value.substring(Math.max(0,value.length()-4)));
        return status(userId);
    }
    @Transactional
    public Status delete(long userId) {
        jdbc.queryForObject("select id from user_accounts where id=? for update",Long.class,userId);
        jdbc.update("delete from user_ai_credentials where user_id=?",userId);return status(userId);
    }
    public String key(long userId) {
        requireMaster();
        List<String[]> rows=jdbc.query("select ciphertext,nonce from user_ai_credentials where user_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},userId);
        if(rows.isEmpty())throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE,"请先在个人中心的 AI 配置中添加你的 DeepSeek Key；当前输入已保留");
        try {return new String(crypt(Cipher.DECRYPT_MODE,userId,Base64.getDecoder().decode(rows.get(0)[1]),Base64.getDecoder().decode(rows.get(0)[0])),StandardCharsets.UTF_8);}
        catch(IllegalArgumentException e){throw unavailable();}
    }
    private byte[] crypt(int mode,long userId,byte[] nonce,byte[] input) {
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,master,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(("FINNS:deepseek:v1:"+userId).getBytes(StandardCharsets.UTF_8));return cipher.doFinal(input);
        } catch(java.security.GeneralSecurityException e){throw unavailable();}
    }
    private void requireMaster(){if(master==null)throw unavailable();}
    private ApiProblem unavailable(){return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE,"个人 AI 凭据存储暂不可用，请联系管理员检查加密配置；当前输入已保留");}
}
