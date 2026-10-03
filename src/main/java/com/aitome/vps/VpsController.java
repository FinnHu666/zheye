package com.aitome.vps;

import com.aitome.user.AuthController;
import com.aitome.user.UserAccount;
import com.aitome.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;

@RestController @RequestMapping("/api/vps")
public class VpsController {
    private final VpsRepository recommendations;private final UserRepository users;
    public VpsController(VpsRepository recommendations,UserRepository users){this.recommendations=recommendations;this.users=users;}
    @GetMapping public List<VpsRecommendation> list(){return recommendations.findTop12ByOrderByScoreDesc();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public VpsRecommendation create(@Valid @RequestBody VpsRequest request,HttpSession session){
        UserAccount user=AuthController.requireUser(session,users);
        return recommendations.save(new VpsRecommendation(request.provider(),request.planName(),request.region(),request.cpu(),request.memoryGb(),request.storageGb(),request.monthlyPrice(),request.score(),request.description(),user.getDisplayName()));
    }
    public record VpsRequest(@NotBlank @Size(max=60) String provider,@NotBlank @Size(max=80) String planName,@NotBlank @Size(max=50) String region,
                             @Min(1) @Max(128) int cpu,@Min(1) @Max(1024) int memoryGb,@Min(10) @Max(100000) int storageGb,
                             @DecimalMin("0.01") BigDecimal monthlyPrice,@DecimalMin("1") @DecimalMax("5") double score,@NotBlank @Size(max=300) String description){}
}
