package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;

@Component
public class DeepSeekClient {
    private final String baseUrl,model;
    private final com.aitome.user.AiCredentialService credentials;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration timeout;
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    public DeepSeekClient(ObjectMapper json,
        com.aitome.user.AiCredentialService credentials,
        @Value("${finns.ai.base-url:${FINNS_AI_BASE_URL:https://api.deepseek.com}}") String baseUrl,
        @Value("${finns.ai.model:${FINNS_AI_MODEL:deepseek-flash}}") String model,
        @Value("${finns.ai.timeout-seconds:60}") int timeoutSeconds) {
        this.json=json;this.credentials=credentials;this.baseUrl=baseUrl.replaceAll("/+$","");this.model=model;
        this.timeout=Duration.ofSeconds(timeoutSeconds);this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    public boolean available(long userId){return credentials.available(userId);}
    public String model(){return model;}
    public record Result(JsonNode value,JsonNode usage,String model,long elapsedMs) {}
    public Result call(long userId,String system,List<Map<String,String>> messages,int maxTokens) {
        String apiKey=credentials.key(userId);
        if(!running.add(userId))throw problem(HttpStatus.TOO_MANY_REQUESTS,"已有一个 AI 请求正在处理，请等待结束后再试");
        long start=System.nanoTime();
        try {
            List<Map<String,String>> all=new ArrayList<>();all.add(Map.of("role","system","content",system));all.addAll(messages);
            Map<String,Object> body=Map.of("model",model,"messages",all,"stream",false,"thinking",Map.of("type","disabled"),"response_format",Map.of("type","json_object"),"max_tokens",maxTokens);
            HttpRequest request=HttpRequest.newBuilder(URI.create(baseUrl+"/chat/completions")).timeout(timeout)
                .header("Content-Type","application/json").header("Authorization","Bearer "+apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var future=http.sendAsync(request,info->new LimitedBody());
            HttpResponse<byte[]> response;
            try { response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
            catch(TimeoutException e){future.cancel(true);throw new HttpTimeoutException("deadline");}
            catch(ExecutionException e){if(e.getCause() instanceof HttpTimeoutException)throw new HttpTimeoutException("deadline");throw invalid();}
            finally{if(!future.isDone())future.cancel(true);}
            {
                if(response.statusCode()!=200) {
                    String reason=switch(response.statusCode()){case 401,403->"你的 DeepSeek Key 鉴权失败，请在个人中心的 AI 配置中替换";case 402->"DeepSeek 账户额度不足";case 429->"DeepSeek 请求受限，请稍后手动重试";default->"DeepSeek 暂时无法完成请求";};
                    throw problem(HttpStatus.BAD_GATEWAY,reason+"；当前输入已保留");
                }
                byte[] bytes=response.body();
                JsonNode root=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);if(root==null||!root.isObject())throw invalid();
                JsonNode choice=root.path("choices").path(0);
                if(!"stop".equals(choice.path("finish_reason").asText()))throw problem(HttpStatus.BAD_GATEWAY,"AI 输出未完整结束，请精简资料后重试；原稿已保留");
                JsonNode content=choice.path("message").get("content");if(content==null||!content.isTextual()||content.asText().isBlank())throw invalid();
                JsonNode value=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content.asText());if(value==null||!value.isObject())throw invalid();
                ObjectNode usage=json.createObjectNode();for(String key:List.of("prompt_tokens","completion_tokens","total_tokens")){JsonNode n=root.path("usage").get(key);if(n!=null&&n.isIntegralNumber()&&n.asLong()>=0)usage.set(key,n);}
                String reportedModel=root.path("model").asText("");
                return new Result(value,usage,reportedModel.isBlank()||reportedModel.length()>100?model:reportedModel,(System.nanoTime()-start)/1_000_000);
            }
        } catch(HttpTimeoutException e){throw problem(HttpStatus.GATEWAY_TIMEOUT,"DeepSeek 请求超时；问卷和原稿已保留，可手动重试");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw problem(HttpStatus.BAD_GATEWAY,"AI 请求已中断；当前输入已保留");}
        catch(java.io.IOException|IllegalArgumentException e){throw invalid();}
        finally{running.remove(userId);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer b:buffers){if(bytes.size()+b.remaining()>1_048_576){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("body limit"));return;}byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private ApiProblem invalid(){return problem(HttpStatus.BAD_GATEWAY,"AI 返回空结果或无效格式；问卷和原稿已保留，请重试");}
    private ApiProblem problem(HttpStatus status,String message){return new ApiProblem(status,message);}
}
