package kr.malsai.conflict;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("swagger")
@RestController
class ConflictSpecController {
    @GetMapping(path = "/openapi/conflict.json", produces = MediaType.APPLICATION_JSON_VALUE)
    Resource conflictSpec() {
        return new ClassPathResource("swagger/conflict.json");
    }
}
