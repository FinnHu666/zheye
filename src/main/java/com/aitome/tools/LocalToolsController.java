package com.aitome.tools;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/private/tools")
public class LocalToolsController {
    private final String key;
    private final String roots;
    public LocalToolsController(@Value("${aitome.tools.key:}") String key,
            @Value("${aitome.tools.roots:D:/env/.codex/skills}") String roots) {
        this.key = key; this.roots = roots;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request,
            @RequestHeader(value="X-Tools-Key", defaultValue="") String supplied) {
        // Private inventory requires a separate owner secret even for a local proxy.
        if (key.isBlank() || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).header("Cache-Control", "no-store").body(Map.of("message", "请输入私人访问密钥；服务端需先设置 AITOME_TOOLS_KEY。"));
        }
        List<Map<String, String>> skills = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (String configured : roots.split(";")) {
            Path root = Path.of(configured.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) { warnings.add("一个配置的技能目录不可用"); continue; }
            try (var files = Files.walk(root, 8)) {
                for (Path file : files.filter(p -> p.getFileName().toString().equals("SKILL.md")).sorted().toList()) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !seen.add(file.toRealPath())) continue;
                    try {
                        if (Files.size(file) > 262144) { warnings.add("跳过过大的技能文件"); continue; }
                        String source = Files.readString(file).replace("\r\n", "\n");
                        Map<?, ?> metadata = Map.of();
                        int end = source.indexOf("\n---", 3);
                        if (source.startsWith("---\n") && end > 3) {
                            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(source.substring(4, end));
                            if (parsed instanceof Map<?, ?> map) metadata = map;
                        }
                        String name = Objects.toString(metadata.get("name"), file.getParent().getFileName().toString());
                        String description = Objects.toString(metadata.get("description"), "暂无说明");
                        skills.add(Map.of("name", name, "description", description, "source", root.getFileName().toString(),
                                "location", root.relativize(file).toString(), "updatedAt", Files.getLastModifiedTime(file).toInstant().toString()));
                    } catch (Exception ex) { warnings.add("一个技能文件解析失败"); }
                }
            } catch (Exception ex) { warnings.add("一个技能目录读取失败"); }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("skills", skills, "total", skills.size(), "warnings", warnings));
    }
}
