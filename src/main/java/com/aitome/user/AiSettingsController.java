package com.aitome.user;

import com.aitome.access.Permission;
import com.aitome.access.RequirePermission;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

@RestController @RequestMapping("/api/me/ai-settings/deepseek")
@RequirePermission(Permission.AI_USE)
public class AiSettingsController {
    private final UserRepository users;
    private final AiCredentialService credentials;
    public AiSettingsController(UserRepository users,AiCredentialService credentials){this.users=users;this.credentials=credentials;}
    public record KeyRequest(String key) {}
    private long user(HttpSession session,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return AuthController.requireUser(session,users).getId();}
    @GetMapping public AiCredentialService.Status get(HttpSession session,HttpServletResponse response){return credentials.status(user(session,response));}
    @PutMapping public AiCredentialService.Status save(@RequestBody KeyRequest request,HttpSession session,HttpServletResponse response){return credentials.save(user(session,response),request.key());}
    @DeleteMapping public AiCredentialService.Status delete(HttpSession session,HttpServletResponse response){return credentials.delete(user(session,response));}
}
